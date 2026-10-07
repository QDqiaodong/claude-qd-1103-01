#!/usr/bin/env python3
"""
会员档案合并 / 联系方式变更审计 —— 端到端验收脚本（真实 HTTP + 真实 MySQL 行锁）。

覆盖：
  1. 建会员 / 联系方式变更审计 / 同手机号不能绑两个有效会员
  2. 标记合并 → 预览两边手机/姓名/积分/历史订单 → 无冲突合并
  3. 手机号冲突（历史同号重复档）→ 冲突待处理 → 负责人解决 → 合并
  4. 积分来源冲突（余额与流水不一致）→ 两种策略
  5. 合并后历史订单/积分流水归属主档、原档只读别名、查询可追溯主档
  6. 撤销合并（仅负责人）→ 订单/积分按原始归属还原、积分不重复计算
  7. 幂等：重复标记 / 重复合并 / 重复撤销
  8. 并发：并发合并 / 并发改手机号
  9. 出单/退款与合并后的积分归属联动
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("BASE", "http://127.0.0.1:8080/api")
STAFF = {"operator": "店员小林", "operatorRole": "店员"}
MANAGER = {"operator": "店长老王", "operatorRole": "负责人"}

PASS = 0
FAIL = 0


def call(method, path, body=None, expect_status=200, headers=None):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as resp:
            payload = json.loads(resp.read().decode())
            status = resp.status
    except urllib.error.HTTPError as e:
        status = e.code
        try:
            payload = json.loads(e.read().decode())
        except Exception:
            payload = {"message": "<non-json>"}
    ok = status == expect_status and payload.get("ok", True)
    return ok, status, payload


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ✅ {name}")
    else:
        FAIL += 1
        print(f"  ❌ {name}  {detail}")


def must(method, path, body=None):
    ok, status, payload = call(method, path, body)
    if not ok:
        raise AssertionError(f"{method} {path} -> {status} {payload}")
    return payload["data"]



def section(t):
    print(f"\n=== {t} ===")


PHONE = int(time.time()) % 100000000


def phone(tag, n):
    return "139%08d" % ((PHONE * 10 + n) % 100000000)


# ----------------------------------------------------------------------
section("1. 建会员 + 联系方式变更审计")
pa, pb = phone("a", 1), phone("b", 2)
a = must("POST", "/members", {"phone": pa, "name": "张三", "points": 100, "level": "普通", **STAFF})
b = must("POST", "/members", {"phone": pb, "name": "张叁(重复)", "points": 50, **STAFF})
print(f"   会员 A=#{a['id']}({a['phone']}) B=#{b['id']}({b['phone']})")

# 同手机号不能再建有效会员
ok, st, pl = call("POST", "/members", {"phone": pa, "name": "冒名", "points": 0, **STAFF})
check("同手机号再次建会员被拒绝", not ok and "手机号" in pl.get("message", ""), pl)

# 缺操作人
ok, st, pl = call("POST", "/members", {"phone": phone("x", 9), "name": "无主", "points": 0})
check("缺操作人被拒绝", not ok and "操作人" in pl.get("message", ""), pl)

# 改姓名（带原因）→ 审计
ver = a["version"]
a2 = must("POST", f"/members/{a['id']}/contact-change",
          {"name": "张三改", "reason": "顾客要求更正姓名", "expectedVersion": ver, **STAFF})
check("姓名变更成功并升版本", a2["name"] == "张三改" and a2["version"] == ver + 1, a2)

# 用旧版本并发防护
ok, st, pl = call("POST", f"/members/{a['id']}/contact-change",
                  {"name": "张三旧版", "reason": "过期表单", "expectedVersion": ver, **STAFF})
check("过期版本改联系方式被拒绝", not ok and "刷新" in pl.get("message", ""), pl)

# 改手机号（带原因）
pa2 = phone("a2", 3)
a3 = must("POST", f"/members/{a['id']}/contact-change",
          {"phone": pa2, "reason": "换手机号", "expectedVersion": a2["version"], **STAFF})
check("手机号变更成功", a3["phone"] == pa2, a3)
ok, st, pl = call("POST", "/members", {"phone": pa2, "name": "抢号", "points": 0, **STAFF})
check("旧/新手机号均无法被第二个有效会员占用", not ok, pl)

prof = must("GET", f"/members/{a['id']}/profile")
fields = [(c["fieldName"], c["beforeValue"], c["afterValue"], c["reason"], c["operator"])
          for c in prof["contactChanges"]]
check("联系方式审计含前后值/原因/操作人",
      ("NAME", "张三", "张三改", "顾客要求更正姓名", "店员小林") in fields
      and any(f[0] == "PHONE" and f[2] == pa2 for f in fields), fields)

# 走旧入口直接改手机号/姓名被拒绝
ok, st, pl = call("PUT", f"/members/{a['id']}", {"phone": pa, "name": "x"})
check("旧 PUT 入口不再静默改手机号/姓名", not ok and "联系方式变更" in pl.get("message", ""), pl)

# ----------------------------------------------------------------------
section("2. 无冲突合并：标记 → 预览 → 执行 → 归属迁移")
# B 有两笔订单：先出单赚积分
ord1 = must("POST", "/cup-orders",
            {"memberId": b["id"], "payType": "微信",
             "items": [{"recipeId": 1, "qty": 1}]})
ord2 = must("POST", "/cup-orders",
            {"memberId": b["id"], "payType": "现金",
             "items": [{"recipeId": 2, "qty": 2}]})
b = must("GET", f"/members/{b['id']}/profile")
b_points = next(x for x in [must("GET", f"/members/{b['id']}/profile")])["points"]
a_points = must("GET", f"/members/{a['id']}/profile")["points"]
print(f"   合并前 A 积分={a_points}, B 积分={b_points}, B 订单 2 笔")

mk = must("POST", "/member-merges",
          {"masterId": a["id"], "slaveId": b["id"], "reason": "同一顾客重复建档", **STAFF})
check("标记后工单为待处理 PENDING", mk["status"] == "PENDING", mk)
check("工单号存在", mk["id"] is not None)

# 重复标记
ok, st, pl = call("POST", "/member-merges",
                  {"masterId": a["id"], "slaveId": b["id"], "reason": "重复点", **STAFF})
check("重复标记被拒绝", not ok and "工单" in pl.get("message", ""), pl)

detail = must("GET", f"/member-merges/{mk['id']}")
check("预览能看到两边手机号/姓名/积分/历史订单数",
      detail["master"]["id"] == a["id"] and detail["slave"]["id"] == b["id"]
      and detail["slave"]["orderCount"] == 2
      and detail["slave"]["ledgerSum"] == b_points
      and detail["master"]["phone"] == pa2 and detail["slave"]["phone"] == pb,
      {"m": detail["master"], "s": detail["slave"]})
check("预检无冲突", detail["precheckConflicts"] == [], detail["precheckConflicts"])

# 店员执行合并
merged = must("POST", f"/member-merges/{mk['id']}/execute",
              {"expectedVersion": mk["version"], **STAFF})
check("合并成功 MERGED", merged["status"] == "MERGED", merged)
check("工单保存合并前快照", json.loads(merged["masterSnapshot"])["phone"] == pa2
      and json.loads(merged["slaveSnapshot"])["name"] == "张叁(重复)", merged)

# 重复执行 = 幂等
again = must("POST", f"/member-merges/{mk['id']}/execute", {"expectedVersion": merged["version"], **STAFF})
check("重复合并幂等返回 MERGED", again["status"] == "MERGED" and again["id"] == mk["id"])

# 归属校验
master_prof = must("GET", f"/members/{a['id']}/profile")
slave_prof = must("GET", f"/members/{b['id']}/profile")
check("主档积分=两边之和（流水不复制）",
      master_prof["points"] == a_points + b_points, master_prof["points"])
master_order_ids = {o["id"] for o in master_prof["orders"]}
check("历史订单统一归主档（含 B 两笔）", {ord1["id"], ord2["id"]} <= master_order_ids,
      master_order_ids)
check("主档流水含合并锚点 MERGE_MARK",
      any(l["changeType"] == "MERGE_MARK" for l in master_prof["ledger"]), "no mark")
check("从档成为只读别名并指向主档",
      slave_prof["status"] == "已合并" and slave_prof["mergedIntoId"] == a["id"], slave_prof)
check("从档详情可追溯到主档", slave_prof["master"] and slave_prof["master"]["id"] == a["id"],
      slave_prof.get("master"))
check("主档可见别名及合并原因/操作人",
      any(al["id"] == b["id"] and al["reason"] == "同一顾客重复建档"
          and al["mergeOperator"] == "店员小林" for al in master_prof["aliases"]),
      master_prof["aliases"])
check("从档联系方式变更历史可在主档审计中追溯",
      any(c.get("afterValue") == pa2 for c in master_prof["contactChanges"]),
      "contact logs not visible")
check("流水合计与主档余额一致（不重复计算）",
      sum(l["pointsDelta"] for l in master_prof["ledger"]) == master_prof["points"],
      (sum(l["pointsDelta"] for l in master_prof["ledger"]), master_prof["points"]))

# 已合并档不能下单/改积分
ok, st, pl = call("POST", "/cup-orders",
                  {"memberId": b["id"], "payType": "微信", "items": [{"recipeId": 1, "qty": 1}]})
check("已合并别名下单被拒绝并提示主档", not ok and "主档#" in pl.get("message", ""), pl)
ok, st, pl = call("POST", f"/members/{b['id']}/points-adjust",
                  {"delta": 1, "reason": "x", **STAFF})
check("已合并别名不能调积分", not ok, pl)

# ----------------------------------------------------------------------
section("3. 撤销合并：仅负责人，还原归属且积分不重复")
ok, st, pl = call("POST", f"/member-merges/{mk['id']}/revoke",
                  {"reason": "合错了", **STAFF})
check("店员撤销被拒绝（仅负责人）", not ok and "负责人" in pl.get("message", ""), pl)

revoked = must("POST", f"/member-merges/{mk['id']}/revoke",
               {"expectedVersion": again["version"], "reason": "核实是两位顾客中的误判", **MANAGER})
check("负责人撤销成功 REVOKED", revoked["status"] == "REVOKED", revoked)

ok, st, pl = call("POST", f"/member-merges/{mk['id']}/revoke",
                  {"reason": "再撤一次", **MANAGER})
check("重复撤销被稳定拒绝", not ok and "重复" in pl.get("message", ""), pl)

a_back = must("GET", f"/members/{a['id']}/profile")
b_back = must("GET", f"/members/{b['id']}/profile")
check("撤销后从档恢复有效且原手机号/姓名还原",
      b_back["status"] == "有效" and b_back["phone"] == pb
      and b_back["name"] == "张叁(重复)", {k: b_back.get(k) for k in ("status", "phone", "name")})
check("撤销后订单还原原归属",
      {o["id"] for o in b_back["orders"]} == {ord1["id"], ord2["id"]}
      and not ({ord1["id"], ord2["id"]} <= {o["id"] for o in a_back["orders"]}),
      "orders not restored")
check("撤销后积分余额还原且不重复计算",
      b_back["points"] == b_points and a_back["points"] == a_points,
      (b_back["points"], a_back["points"]))
check("撤销摘要含还原数量", "还原订单 2 笔" in (revoked.get("revokeSummary") or ""),
      revoked.get("revokeSummary"))
check("撤销后有 REVOKE_MARK 审计锚点",
      any(l["changeType"] == "REVOKE_MARK" for l in b_back["ledger"]), "no revoke mark")

# 已撤销工单不能再执行
ok, st, pl = call("POST", f"/member-merges/{mk['id']}/execute", {**STAFF})
check("已撤销工单不能再执行合并", not ok and "重新标记" in pl.get("message", ""), pl)

# 撤销后可以重新合并
mk2 = must("POST", "/member-merges",
           {"masterId": a["id"], "slaveId": b["id"], "reason": "二次确认确实重复", **STAFF})
merged2 = must("POST", f"/member-merges/{mk2['id']}/execute", {**STAFF})
check("撤销后重新标记并合并成功", merged2["status"] == "MERGED", merged2)

# ----------------------------------------------------------------------
section("4. 手机号冲突：历史同号重复档 → CONFLICT → 负责人解决")
pc = phone("c", 4)
c1 = must("POST", "/members", {"phone": pc, "name": "王五", "points": 300, **STAFF})
# 负责人导入历史同号重复档
c2 = must("POST", "/members/legacy-import",
          {"phone": pc, "name": "王五(旧卡)", "points": 80, "reason": "旧收银系统导出", **MANAGER})
ok, st, pl = call("POST", "/members/legacy-import",
                  {"phone": phone("z", 0), "name": "越权", "points": 1, "reason": "x", **STAFF})
check("店员不能导入历史档", not ok and "负责人" in pl.get("message", ""), pl)

cm = must("POST", "/member-merges",
          {"masterId": c1["id"], "slaveId": c2["id"], "reason": "旧卡与新卡同号", **STAFF})
check("同号合并进入冲突待处理 CONFLICT", cm["status"] == "CONFLICT"
      and "PHONE_CONFLICT" in (cm.get("conflictTypes") or ""), cm)
cdetail = must("GET", f"/member-merges/{cm['id']}")
check("冲突详情能看到两边手机号（相同）",
      cdetail["master"]["phone"] == pc and cdetail["slave"]["phone"] == pc, cdetail)
ok, st, pl = call("POST", f"/member-merges/{cm['id']}/execute", {**STAFF})
check("冲突未解决不能合并", not ok and "冲突待处理" in pl.get("message", ""), pl)
ok, st, pl = call("POST", f"/member-merges/{cm['id']}/resolve",
                  {"keepPhone": pc, "reason": "确认保留", **STAFF})
check("店员不能解决冲突", not ok and "负责人" in pl.get("message", ""), pl)
# 必须明确选号
ok, st, pl = call("POST", f"/member-merges/{cm['id']}/resolve",
                  {"reason": "随便", **MANAGER})
check("未明确保留手机号不能解决", not ok and "保留哪边" in pl.get("message", ""), pl)
resolved = must("POST", f"/member-merges/{cm['id']}/resolve",
                {"keepPhone": pc, "reason": "核实同人，保留主档号码", **MANAGER})
check("解决后回 PENDING", resolved["status"] == "PENDING", resolved)
cmerged = must("POST", f"/member-merges/{cm['id']}/execute", {**STAFF})
check("冲突解决后合并成功", cmerged["status"] == "MERGED", cmerged)
c_master = must("GET", f"/members/{c1['id']}/profile")
c_slave = must("GET", f"/members/{c2['id']}/profile")
check("主档保留手机号且积分合并", c_master["phone"] == pc and c_master["points"] == 380,
      (c_master["phone"], c_master["points"]))
check("同号从档别名加后缀只读保留", c_slave["phone"].startswith(pc) and "已归档" in c_slave["phone"],
      c_slave["phone"])
# 该手机号仍只绑一个有效会员
holders = must("GET", f"/members?status=%E6%9C%89%E6%95%88")
same = [m for m in holders if m["phone"] == pc]
check("合并后该手机号只有一个有效会员", len(same) == 1 and same[0]["id"] == c1["id"], same)

# ----------------------------------------------------------------------
section("5. 积分来源冲突：余额与流水不一致 → 必须选择策略（两种）")
import subprocess

MYSQL = ["/tmp/tools/mysql-8.4.6-linux-glibc2.28-aarch64/bin/mysql",
         "--socket=/tmp/mysqlrun/mysql.sock", "-uadmin", "-ppassword", "milktea_bar", "-N", "-e"]


def db_exec(sql):
    r = subprocess.run(MYSQL + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(f"SQL failed: {sql}\n{r.stderr}")
    return r.stdout.strip()


def tamper_points(member_id, points):
    """模拟线下系统脏数据：只改余额不写流水（外部篡改/历史数据不一致）。"""
    db_exec(f"UPDATE member SET points={points} WHERE id={member_id}")


# 策略一：TRUST_BALANCE（以当前余额为准，补调平流水）
pd = phone("d", 5)
d1 = must("POST", "/members", {"phone": pd, "name": "赵六", "points": 100, **STAFF})
d2 = must("POST", "/members/legacy-import",
          {"phone": phone("d2", 6), "name": "赵六(旧)", "points": 70, "reason": "旧系统", **MANAGER})
tamper_points(d2["id"], 120)   # 余额 120，流水合计 70 → 冲突
dm = must("POST", "/member-merges",
          {"masterId": d1["id"], "slaveId": d2["id"], "reason": "赵六重复", **STAFF})
check("余额与流水不一致时进入积分来源冲突",
      dm["status"] == "CONFLICT" and "POINTS_SOURCE" in (dm.get("conflictTypes") or "")
      and "120" in (dm.get("conflictDetail") or "") and "70" in (dm.get("conflictDetail") or ""),
      dm)
ok, st, pl = call("POST", f"/member-merges/{dm['id']}/execute", {**STAFF})
check("积分冲突未解决不能合并", not ok and "冲突待处理" in pl.get("message", ""), pl)
ok, st, pl = call("POST", f"/member-merges/{dm['id']}/resolve",
                  {"pointsStrategy": "BAD_STRATEGY", "reason": "乱选", **MANAGER})
check("非法积分策略被拒绝", not ok and "策略" in pl.get("message", ""), pl)
resolved = must("POST", f"/member-merges/{dm['id']}/resolve",
                {"pointsStrategy": "TRUST_BALANCE",
                 "reason": "核对纸质账单，120 余额为准", **MANAGER})
check("解决积分冲突后回 PENDING", resolved["status"] == "PENDING", resolved)
dmerged = must("POST", f"/member-merges/{dm['id']}/execute", {**STAFF})
dmaster = must("GET", f"/members/{d1['id']}/profile")
check("TRUST_BALANCE：主档积分=100+120=220，调平流水 50 已补",
      dmerged["status"] == "MERGED" and dmaster["points"] == 220
      and any(l["changeType"] == "MANUAL" and l["pointsDelta"] == 50
              and l["originMemberId"] == d2["id"] for l in dmaster["ledger"]),
      (dmaster["points"], [(l["changeType"], l["pointsDelta"]) for l in dmaster["ledger"]]))
check("TRUST_BALANCE：主档流水合计=余额",
      sum(l["pointsDelta"] for l in dmaster["ledger"]) == dmaster["points"], "mismatch")

# 策略二：REBUILD_FROM_LEDGER（以流水合计为准重建余额，差异留痕）
pe2, pf2 = phone("e", 13), phone("f", 14)
e3 = must("POST", "/members", {"phone": pe2, "name": "吴九", "points": 200, **STAFF})
e4 = must("POST", "/members/legacy-import",
          {"phone": pf2, "name": "吴九(旧)", "points": 40, "reason": "旧系统", **MANAGER})
tamper_points(e4["id"], 90)    # 余额 90，流水合计 40
em2 = must("POST", "/member-merges",
           {"masterId": e3["id"], "slaveId": e4["id"], "reason": "吴九重复", **STAFF})
check("第二组同样识别积分冲突", em2["status"] == "CONFLICT", em2)
must("POST", f"/member-merges/{em2['id']}/resolve",
     {"pointsStrategy": "REBUILD_FROM_LEDGER", "reason": "流水可追溯，以流水为准", **MANAGER})
must("POST", f"/member-merges/{em2['id']}/execute", {**STAFF})
emaster = must("GET", f"/members/{e3['id']}/profile")
check("REBUILD_LEDGER：主档积分=200+40=240（不采信虚高的 90）",
      emaster["points"] == 240, emaster["points"])
check("REBUILD_LEDGER：差异 50 在主档流水有重建留痕",
      any("积分来源重建" in (l["remark"] or "") for l in emaster["ledger"]),
      [l["remark"] for l in emaster["ledger"]])

# ----------------------------------------------------------------------
section("6. 四态筛选")
# 前面的工单都已执行/撤销，这里再造一张待处理与一张冲突待处理
fi1 = must("POST", "/members", {"phone": phone("fp", 20), "name": "待合甲", "points": 0, **STAFF})
fi2 = must("POST", "/members", {"phone": phone("fp", 21), "name": "待合乙", "points": 0, **STAFF})
pending_mk = must("POST", "/member-merges",
                  {"masterId": fi1["id"], "slaveId": fi2["id"], "reason": "待处理筛选用", **STAFF})
check("待处理筛选夹具为 PENDING", pending_mk["status"] == "PENDING", pending_mk)
fi3 = must("POST", "/members", {"phone": phone("fc", 22), "name": "冲突甲", "points": 0, **STAFF})
fi4 = must("POST", "/members/legacy-import",
           {"phone": phone("fc", 22), "name": "冲突乙", "points": 0,
            "reason": "冲突筛选用", **MANAGER})
conflict_mk = must("POST", "/member-merges",
                   {"masterId": fi3["id"], "slaveId": fi4["id"], "reason": "冲突筛选用", **STAFF})
check("冲突筛选夹具为 CONFLICT", conflict_mk["status"] == "CONFLICT", conflict_mk)

for label, st_atom in [("待处理", "PENDING"), ("冲突待处理", "CONFLICT"),
                       ("已合并", "MERGED"), ("已撤销", "REVOKED")]:
    rows = must("GET", f"/member-merges?status={st_atom}")
    check(f"筛选 {label}({st_atom})", all(r["status"] == st_atom for r in rows) and len(rows) >= 1,
          f"{len(rows)} rows")

# ----------------------------------------------------------------------
section("7. 并发：同档并发合并只能成一张工单 / 一次执行")
pe, pf = phone("e", 7), phone("f", 8)
e1 = must("POST", "/members", {"phone": pe, "name": "钱七", "points": 10, **STAFF})
e2 = must("POST", "/members", {"phone": pf, "name": "钱七(重复)", "points": 20, **STAFF})


def post_mark(_):
    return call("POST", "/member-merges",
                {"masterId": e1["id"], "slaveId": e2["id"], "reason": "并发标记", **STAFF})


with ThreadPoolExecutor(max_workers=6) as ex:
    results = list(ex.map(post_mark, range(6)))
wins = [r for r in results if r[0] and r[2]["data"]["status"] in ("PENDING", "CONFLICT")]
check("6 个并发标记只有 1 张开放工单", len(wins) == 1, [(r[1], r[2].get("message", "")[:30]) for r in results])
emk = wins[0][2]["data"]


def post_execute(_):
    return call("POST", f"/member-merges/{emk['id']}/execute",
                {"expectedVersion": emk["version"], **STAFF})


with ThreadPoolExecutor(max_workers=6) as ex:
    r2 = list(ex.map(post_execute, range(6)))
merged_wins = [r for r in r2 if r[0] and r[2]["data"]["status"] == "MERGED"]
check("6 个并发执行合并结果稳定（成功+幂等，无双重迁移）",
      len(merged_wins) >= 1, [(x[1], x[2].get("message", "")[:40]) for x in r2])
e_master = must("GET", f"/members/{e1['id']}/profile")
check("并发后积分只合并一次", e_master["points"] == 30, e_master["points"])
e_slave = must("GET", f"/members/{e2['id']}/profile")
check("并发后从档只归档一次", e_slave["status"] == "已合并" and e_slave["mergedIntoId"] == e1["id"],
      e_slave["status"])

# ----------------------------------------------------------------------
section("8. 并发：两个会员并发改成同一手机号，只有一个成功")
pg, ph = phone("g", 10), phone("h", 11)
g = must("POST", "/members", {"phone": pg, "name": "孙八", "points": 0, **STAFF})
h = must("POST", "/members", {"phone": ph, "name": "周九", "points": 0, **STAFF})
target = phone("target", 12)
g0 = must("GET", f"/members/{g['id']}/profile")
h0 = must("GET", f"/members/{h['id']}/profile")


def change_g(_):
    return call("POST", f"/members/{g['id']}/contact-change",
                {"phone": target, "reason": "并发改号G", "expectedVersion": g0["version"], **STAFF})


def change_h(_):
    return call("POST", f"/members/{h['id']}/contact-change",
                {"phone": target, "reason": "并发改号H", "expectedVersion": h0["version"], **STAFF})


with ThreadPoolExecutor(max_workers=2) as ex:
    futures = [ex.submit(change_g, 0), ex.submit(change_h, 0)]
    cr = [f.result() for f in futures]
successes = [r for r in cr if r[0] and r[2]["data"]["phone"] == target]
check("并发改同号恰好 1 个成功", len(successes) == 1,
      [(r[1], r[2].get("message", "")[:50]) for r in cr])
holders = must("GET", f"/members?status=%E6%9C%89%E6%95%88")
same = [m for m in holders if m["phone"] == target]
check("该手机号只绑定一个有效会员", len(same) == 1, same)

# ----------------------------------------------------------------------
section("9. 合并后退款：从档历史订单退款，积分回主档且撤销可还原")
# 用第 2 节二次合并后的 a/b（b 在 a 名下）。b 的 ord1 为“已出杯”，按主档退款
before_pts = must("GET", f"/members/{a['id']}/profile")["points"]
# 订单#ord1 是 recipe1 x1 = 12 元
rf = must("POST", f"/cup-orders/{ord1['id']}/refund")
check("合并后对历史订单退款成功", rf["status"] == "已退款", rf)
after = must("GET", f"/members/{a['id']}/profile")
check("退款积分回到主档（流水 origin 仍为原从档）",
      after["points"] == before_pts - (ord1["pointsEarned"] or 12),
      (after["points"], before_pts))
refund_ledger = [l for l in after["ledger"] if l["refId"] == ord1["id"] and l["changeType"] == "REFUND"]
check("退款流水 originMemberId 为原从档",
      refund_ledger and refund_ledger[0]["originMemberId"] == b["id"], refund_ledger)

# 撤销售二次合并：积分按流水还原，主档退款动作发生在合并期间——
# REFUND 流水 origin=b 会随从档还原，b 余额按流水重建
rev2 = must("POST", f"/member-merges/{mk2['id']}/revoke",
            {"reason": "退款后仍需撤销", **MANAGER})
b_final = must("GET", f"/members/{b['id']}/profile")
a_final = must("GET", f"/members/{a['id']}/profile")
check("撤销后订单与退款流水一起回到原档",
      any(o["id"] == ord1["id"] and o["status"] == "已退款" for o in b_final["orders"]),
      "refunded order not restored")
check("两边流水各自合计=余额（积分全程只算一次）",
      sum(l["pointsDelta"] for l in b_final["ledger"]) == b_final["points"]
      and sum(l["pointsDelta"] for l in a_final["ledger"]) == a_final["points"],
      (b_final["points"], a_final["points"]))

# ----------------------------------------------------------------------
section("10. 详情页差异与审计链路")
det = must("GET", f"/member-merges/{mk2['id']}")
check("已撤销工单详情含合并前后快照与撤销摘要",
      det["masterSnapshot"] and det["slaveSnapshot"] and det["revokeSummary"]
      and det["revokeOperator"] == "店长老王", {k: det.get(k) for k in
      ("status", "revokeOperator", "revokeSummary")})

print(f"\n{'='*60}\n结果：✅ {PASS} 通过，❌ {FAIL} 失败")

# ----------------------------------------------------------------------
section("11. 数据库级不变量（直接查 MySQL）")
active_mismatch = int(db_exec("""
SELECT COUNT(*) FROM member m
WHERE m.status='有效' AND m.points <>
  (SELECT COALESCE(SUM(l.points_delta),0) FROM member_points_ledger l WHERE l.member_id=m.id)
"""))
check("有效会员余额恒等于其流水合计", active_mismatch == 0, f"mismatch={active_mismatch}")

guard_bad = int(db_exec("""
SELECT COUNT(*) FROM member_phone_guard g
LEFT JOIN member m ON m.id=g.member_id WHERE m.id IS NULL OR m.status<>'有效'
"""))
check("守卫行始终指向有效会员", guard_bad == 0, f"bad={guard_bad}")

active_missing_guard = int(db_exec("""
SELECT COUNT(*) FROM member m WHERE m.status='有效'
AND m.phone NOT IN (SELECT phone FROM member_phone_guard)
"""))
check("有效会员均被守卫覆盖（历史同号冲突档也有明确归属）", active_missing_guard == 0,
      f"missing={active_missing_guard}")

# 同号的有效重复档必须全部挂在开放冲突工单上（在线接口永不允许产生，仅历史导入+待处理状态存在）
unmanaged_dup = int(db_exec("""
SELECT COUNT(*) FROM (
  SELECT phone, GROUP_CONCAT(id) ids FROM member WHERE status='有效' GROUP BY phone HAVING COUNT(*)>1
) d
WHERE NOT EXISTS (
  SELECT 1 FROM member_merge_request r WHERE r.status IN ('PENDING','CONFLICT')
  AND FIND_IN_SET(r.master_id, REPLACE(d.ids,',',','))>=0
)
"""))
# 上面的 FIND_IN_SET 写法对组内判断不严谨，改用应用层逐组核对
dup_groups = db_exec("SELECT phone FROM member WHERE status='有效' GROUP BY phone HAVING COUNT(*)>1")
unmanaged = 0
for ph in (dup_groups.splitlines() if dup_groups else []):
    ids = [int(x) for x in db_exec(
        f"SELECT GROUP_CONCAT(id) FROM member WHERE status='有效' AND phone='{ph}'").split(",")]
    managed = db_exec(
        f"SELECT COUNT(*) FROM member_merge_request WHERE status IN ('PENDING','CONFLICT') "
        f"AND ((master_id={ids[0]} AND slave_id={ids[1]}) OR (master_id={ids[1]} AND slave_id={ids[0]}))")
    if int(managed) == 0:
        unmanaged += 1
check("同号重复档均处于开放合并工单（冲突待处理）中，不存在游离重复", unmanaged == 0, f"unmanaged={unmanaged}")

alias_orphan = int(db_exec("""
SELECT COUNT(*) FROM member WHERE status='已合并' AND merged_into_id IS NULL
"""))
check("已合并别名必有主档指针", alias_orphan == 0, f"orphan={alias_orphan}")

print(f"\n{'='*60}\n最终结果：✅ {PASS} 通过，❌ {FAIL} 失败")
sys.exit(1 if FAIL else 0)
