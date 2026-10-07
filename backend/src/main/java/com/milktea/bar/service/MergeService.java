package com.milktea.bar.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.dto.MergeExecuteRequest;
import com.milktea.bar.dto.MergeMarkRequest;
import com.milktea.bar.dto.MergeResolveRequest;
import com.milktea.bar.dto.MergeRevokeRequest;
import com.milktea.bar.dto.MemberMergeDetail;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberMergeRequest;
import com.milktea.bar.repository.CupOrderRepository;
import com.milktea.bar.repository.MemberContactChangeLogRepository;
import com.milktea.bar.repository.MemberMergeRequestRepository;
import com.milktea.bar.repository.MemberPointsLedgerRepository;
import com.milktea.bar.repository.MemberRepository;

/**
 * 会员档案合并 / 撤销 / 冲突处理。
 *
 * <p>状态机：PENDING（待处理）→ MERGED（已合并）→ REVOKED（已撤销，仅负责人）；
 * 手机号冲突或积分来源对不上时进入 CONFLICT（冲突待处理），解决后回 PENDING。</p>
 *
 * <p>所有写操作在单事务内对工单行、会员行（按 id 升序）、手机号守卫行（按号码升序）
 * 加悲观写锁，并以 JPA 乐观版本复核；重复合并 / 并发合并 / 并发改手机号 / 重复撤销
 * 都由锁与状态机稳定挡住一个，绝不静默覆盖。</p>
 *
 * <p>合并时历史订单、积分流水只做“归属迁移”不复制；撤销时按原始归属字段还原，
 * 因此积分在任何时刻都只被计算一次。</p>
 */
@Service
public class MergeService {

    private final MemberMergeRequestRepository requestRepository;
    private final MemberRepository memberRepository;
    private final MemberPointsLedgerRepository ledgerRepository;
    private final CupOrderRepository orderRepository;
    private final MemberContactChangeLogRepository contactRepository;
    private final MemberService memberService;
    private final PointsLedgerService ledgerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MergeService(MemberMergeRequestRepository requestRepository,
                        MemberRepository memberRepository,
                        MemberPointsLedgerRepository ledgerRepository,
                        CupOrderRepository orderRepository,
                        MemberContactChangeLogRepository contactRepository,
                        MemberService memberService,
                        PointsLedgerService ledgerService) {
        this.requestRepository = requestRepository;
        this.memberRepository = memberRepository;
        this.ledgerRepository = ledgerRepository;
        this.orderRepository = orderRepository;
        this.contactRepository = contactRepository;
        this.memberService = memberService;
        this.ledgerService = ledgerService;
    }

    // ============================ 列表 / 详情 ============================

    @Transactional(readOnly = true)
    public List<MemberMergeRequest> list(String status) {
        if (status != null && !status.isBlank()) {
            if (!Dict.isValidMergeStatus(status)) {
                throw new BizException("合并工单状态必须是：PENDING / CONFLICT / MERGED / REVOKED");
            }
            return requestRepository.findByStatusOrderByIdDesc(status);
        }
        return requestRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public MemberMergeDetail detail(Long id) {
        MemberMergeRequest req = requestRepository.findById(id)
                .orElseThrow(() -> new BizException("合并工单不存在：" + id));
        MemberMergeDetail dto = toDetail(req);
        // 开放工单附带实时预检结果，页面在执行前能看到最新冲突
        if (Dict.MERGE_PENDING.equals(req.status) || Dict.MERGE_CONFLICT.equals(req.status)) {
            Member master = memberRepository.findById(req.masterId).orElse(null);
            Member slave = memberRepository.findById(req.slaveId).orElse(null);
            dto.precheckConflicts = detectConflicts(master, slave, req);
        } else {
            dto.precheckConflicts = List.of();
        }
        return dto;
    }

    // ============================ 标记为待合并 ============================

    /**
     * 店员把重复会员标记为待合并并选择主档。立即做手机号冲突与积分来源检查，
     * 有冲突则工单直接落 CONFLICT（不静默覆盖），无冲突落 PENDING。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeRequest mark(MergeMarkRequest req) {
        if (req == null) {
            throw new BizException("标记内容不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);
        if (req.masterId == null || req.slaveId == null) {
            throw new BizException("必须同时指定保留的主档与待并入的重复档");
        }
        if (req.masterId.equals(req.slaveId)) {
            throw new BizException("主档与重复档不能是同一会员");
        }
        if (req.reason == null || req.reason.isBlank()) {
            throw new BizException("合并必须填写原因");
        }

        // 按 id 升序加锁会员行，与所有其他会员写路径保持一致，杜绝交叉死锁
        Member first = lockMember(min(req.masterId, req.slaveId));
        Member second = lockMember(max(req.masterId, req.slaveId));
        Member master = req.masterId < req.slaveId ? first : second;
        Member slave = req.masterId < req.slaveId ? second : first;
        requireActive(master, "主档");
        requireActive(slave, "重复档");

        requestRepository.findFirstByMasterIdAndSlaveIdAndStatusIn(
                req.masterId, req.slaveId, List.of(Dict.MERGE_PENDING, Dict.MERGE_CONFLICT))
                .ifPresent(r -> {
                    throw new BizException("这两张档案已存在"
                            + (Dict.MERGE_CONFLICT.equals(r.status) ? "冲突待处理" : "待处理")
                            + "合并工单#" + r.id + "，请勿重复标记");
                });
        List<MemberMergeRequest> slaveBusy = requestRepository.findOpenBySlave(
                req.slaveId, List.of(Dict.MERGE_PENDING, Dict.MERGE_CONFLICT));
        if (!slaveBusy.isEmpty()) {
            throw new BizException("会员#" + req.slaveId + " 已在另一张合并工单#"
                    + slaveBusy.get(0).id + " 中等待处理，不能重复合并");
        }
        if (Dict.MEMBER_MERGED.equals(master.mergedIntoId != null ? master.status : "")) {
            throw new BizException("主档不能是已合并归档的别名档案");
        }

        List<String> conflicts = detectConflicts(master, slave, null);
        MemberMergeRequest saved = new MemberMergeRequest();
        saved.masterId = master.id;
        saved.slaveId = slave.id;
        saved.openKey = openKey(master.id, slave.id);
        saved.reason = trim(req.reason, 255);
        saved.operator = operator.name;
        saved.createdAt = LocalDateTime.now();
        if (conflicts.isEmpty()) {
            saved.status = Dict.MERGE_PENDING;
        } else {
            applyConflict(saved, conflicts);
        }
        try {
            return requestRepository.saveAndFlush(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 并发标记同一对档案：会员行锁已基本串行，此处再兜唯一索引/死锁后的稳定提示
            throw new BizException("这两张档案已存在待处理/冲突待处理的合并工单，请勿重复标记");
        }
    }

    private static String openKey(Long masterId, Long slaveId) {
        return masterId + "-" + slaveId;
    }

    // ============================ 解决冲突（仅负责人） ============================

    /**
     * 冲突待处理的解决入口。负责人显式选择主档保留的手机号与积分来源处理策略后，
     * 工单回到 PENDING；手机号守卫的实际改绑在执行合并事务内完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeRequest resolve(Long id, MergeResolveRequest req) {
        if (req == null) {
            throw new BizException("解决内容不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);
        operator.requireManager("合并冲突处理");

        MemberMergeRequest mr = requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("合并工单不存在：" + id));
        if (!Dict.MERGE_CONFLICT.equals(mr.status)) {
            throw new BizException("工单当前状态为「" + mr.status + "」，只有冲突待处理工单需要解决");
        }
        Member master = lockMember(mr.masterId);
        Member slave = lockMember(mr.slaveId);
        if (master == null || slave == null) {
            throw new BizException("主档或重复档已不存在，无法处理");
        }

        List<String> types = splitTypes(mr.conflictTypes);
        if (types.contains(Dict.CONFLICT_PHONE)) {
            if (req.keepPhone == null
                    || (!req.keepPhone.equals(master.phone) && !req.keepPhone.equals(slave.phone))) {
                throw new BizException("必须明确选择主档保留哪边的手机号（"
                        + master.phone + " / " + slave.phone + "），不能静默覆盖");
            }
            // 选定号码若被本对档案之外的第三个有效会员占用，仍无法解决
            if (memberRepository.existsPhoneConflictOutside(
                    req.keepPhone, Dict.MEMBER_ACTIVE, List.of(master.id, slave.id))) {
                throw new BizException("手机号 " + req.keepPhone
                        + " 还被其他有效会员占用，请先在线下核实后再处理");
            }
            mr.resolvedPhone = req.keepPhone;
        }
        if (types.contains(Dict.CONFLICT_POINTS)) {
            if (!Dict.isValidPointsStrategy(req.pointsStrategy)) {
                throw new BizException("必须选择积分来源处理策略：TRUST_BALANCE（以当前余额补调平流水）"
                        + " / REBUILD_FROM_LEDGER（以流水合计重建余额）");
            }
            mr.pointsStrategy = req.pointsStrategy;
        }
        if (req.reason == null || req.reason.isBlank()) {
            throw new BizException("冲突处理必须填写处理说明");
        }
        String note = trim(req.reason, 255);
        mr.conflictDetail = trim(mr.conflictDetail + "｜处理说明（" + operator.name + "）：" + note, 1000);
        mr.status = Dict.MERGE_PENDING;
        return requestRepository.save(mr);
    }

    // ============================ 执行合并 ============================

    /**
     * 执行合并（单事务）：
     * <ol>
     *   <li>锁工单、锁两档案（id 升序），按最新版本复核状态与档案；</li>
     *   <li>重新做冲突检查（合并前最后一道关），仍有冲突则转 CONFLICT 不覆盖；</li>
     *   <li>积分来源按已确认策略调平；手机号按号码升序锁守卫后改绑/释放；</li>
     *   <li>历史订单、积分流水、联系方式审计统一迁移归属主档；</li>
     *   <li>原档置“已合并”只读别名（保留手机/姓名/积分快照、主档指针、时间），
     *       工单落 MERGED 并保存合并前 JSON 快照与结果摘要。</li>
     * </ol>
     * 对已合并工单的重复提交做幂等返回；并发执行由工单行锁 + 状态机只放行一个。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeRequest execute(Long id, MergeExecuteRequest req) {
        if (req == null) {
            throw new BizException("执行内容不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);

        MemberMergeRequest mr = requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("合并工单不存在：" + id));
        if (req.expectedVersion != null && !req.expectedVersion.equals(mr.version)) {
            throw new BizException("工单信息已被其他人更新（页面版本 " + req.expectedVersion
                    + "，最新版本 " + mr.version + "），请刷新后重试");
        }
        if (Dict.MERGE_MERGED.equals(mr.status)) {
            // 幂等：重复合并返回已完成工单，不再迁移任何数据
            return mr;
        }
        if (Dict.MERGE_REVOKED.equals(mr.status)) {
            throw new BizException("该合并已撤销，不能再次执行；如确需合并请重新标记");
        }

        Member first = lockMember(min(mr.masterId, mr.slaveId));
        Member second = lockMember(max(mr.masterId, mr.slaveId));
        Member master = mr.masterId < mr.slaveId ? first : second;
        Member slave = mr.masterId < mr.slaveId ? second : first;
        if (master == null || slave == null) {
            throw new BizException("主档或重复档已不存在，无法合并");
        }
        requireActive(master, "主档");
        requireActive(slave, "重复档");

        List<String> conflicts = detectConflicts(master, slave, mr);
        if (!conflicts.isEmpty()) {
            applyConflict(mr, conflicts);
            requestRepository.save(mr);
            throw new BizException("合并前检查未通过，工单已转为「冲突待处理」：" + String.join("；", conflicts));
        }

        // —— 1) 积分来源调平（冲突已解决时按策略；主档/从档各自核对，无冲突直接进入迁移） ——
        reconcilePoints(master, mr, operator, "主档");
        reconcilePoints(slave, mr, operator, "重复档");
        int slavePoints = slave.points;
        int masterBefore = master.points;

        // —— 2) 手机号：确定主档最终号码，按号码升序锁守卫 ——
        String targetPhone = mr.resolvedPhone != null ? mr.resolvedPhone : master.phone;
        boolean samePhone = master.phone.equals(slave.phone);
        // 需要释放的号码：不同号时主档/从档中未被保留的那个；同号不释放
        String releaseGuard = samePhone ? null
                : (slave.phone.equals(targetPhone) ? master.phone : slave.phone);
        // 先按号码升序锁相关守卫行，再改绑，避免并发交叉
        List<String> orderedPhones = new ArrayList<>(List.of(targetPhone));
        if (releaseGuard != null && !releaseGuard.equals(targetPhone)) {
            orderedPhones.add(releaseGuard);
        }
        orderedPhones.sort(String::compareTo);
        for (String p : orderedPhones) {
            memberService.touchGuard(p);
        }
        // 目标号守卫指向主档（同号时本就指向其中一边，改绑到主档）
        memberService.rebindPhone(targetPhone, master.id);
        if (releaseGuard != null) {
            memberService.releasePhone(releaseGuard,
                    releaseGuard.equals(master.phone) ? master.id : slave.id);
        }
        master.phone = targetPhone;

        // —— 3) 归属迁移：订单 / 流水 / 联系方式审计 ——
        long slaveOrders = orderRepository.countByMemberId(slave.id);
        int movedOrders = orderRepository.reassignOwner(slave.id, master.id);
        int movedLedgers = ledgerRepository.reassignOwnerByMember(slave.id, master.id);
        contactRepository.pointCurrentToMaster(slave.id, master.id);

        // —— 4) 主档积分 = 原主档余额 + 从档全部积分（流水合计），等级重算 ——
        master.points = masterBefore + slavePoints;
        master.level = Dict.decideMemberLevel(master.points);
        memberRepository.save(master);

        // 合并锚点流水（0 积分，仅审计，不参与计算）
        ledgerService.appendMark(master.id, slave.id, Dict.LEDGER_MERGE_MARK, "MERGE", mr.id,
                "合并归档：会员#" + slave.id + "（" + slave.phone + " / " + slave.name
                        + "）并入本档，迁入积分 " + slavePoints + "、订单 " + movedOrders + " 笔",
                operator.name);

        // —— 5) 原档只读别名 ——
        String aliasPhone = slave.phone;
        slave.status = Dict.MEMBER_MERGED;
        slave.mergedIntoId = master.id;
        slave.mergedAt = LocalDateTime.now();
        // 别名保留 phone 快照；若最终保留号正是从档号码，主档已占用该号，别名加 [已归档] 后缀以免与守卫混淆
        if (aliasPhone.equals(targetPhone)) {
            slave.phone = aliasPhone + "(已归档)";
        }
        memberRepository.save(slave);

        // —— 6) 工单收尾与审计快照 ——
        mr.status = Dict.MERGE_MERGED;
        mr.openKey = null;
        mr.mergeOperator = operator.name;
        mr.mergedAt = LocalDateTime.now();
        mr.masterSnapshot = snapshot(master.id, targetPhone, master.name,
                masterBefore, Dict.decideMemberLevel(masterBefore));
        mr.slaveSnapshot = snapshot(slave.id, aliasPhone, slave.name, slavePoints, slave.level);
        mr.resultSummary = trim("主档积分 " + masterBefore + " + 从档积分 " + slavePoints
                + " = " + master.points + "；迁移历史订单 " + movedOrders + " 笔（从档共 "
                + slaveOrders + " 笔）、积分流水 " + movedLedgers + " 条；主档保留手机号 "
                + targetPhone, 1000);
        return requestRepository.save(mr);
    }

    // ============================ 撤销合并（仅负责人） ============================

    /**
     * 撤销合并（单事务）：仅负责人可发起。
     * <ol>
     *   <li>锁工单与两档案，确认工单已合并且档案仍为别名关系；</li>
     *   <li>校验从档原手机号当前可恢复（未被主档后续改号/其他会员占用）；</li>
     *   <li>原始属于从档的订单与流水按 origin 字段还原归属（合并后才赚到的积分留主档，
     *       两边各自按流水合计重建余额，积分不重复计算）；</li>
     *   <li>从档恢复有效、恢复原手机号与守卫绑定，工单落 REVOKED。</li>
     * </ol>
     * 重复撤销由状态机稳定拒绝；并发撤销由工单行锁串行。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeRequest revoke(Long id, MergeRevokeRequest req) {
        if (req == null) {
            throw new BizException("撤销内容不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);
        operator.requireManager("撤销合并");
        if (req.reason == null || req.reason.isBlank()) {
            throw new BizException("撤销合并必须填写原因");
        }

        MemberMergeRequest mr = requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("合并工单不存在：" + id));
        if (req.expectedVersion != null && !req.expectedVersion.equals(mr.version)) {
            throw new BizException("工单信息已被其他人更新（页面版本 " + req.expectedVersion
                    + "，最新版本 " + mr.version + "），请刷新后重试");
        }
        if (Dict.MERGE_REVOKED.equals(mr.status)) {
            throw new BizException("该合并已撤销，请勿重复操作");
        }
        if (!Dict.MERGE_MERGED.equals(mr.status)) {
            throw new BizException("只有已合并工单可以撤销，当前状态：" + mr.status);
        }

        Member first = lockMember(min(mr.masterId, mr.slaveId));
        Member second = lockMember(max(mr.masterId, mr.slaveId));
        Member master = mr.masterId < mr.slaveId ? first : second;
        Member slave = mr.masterId < mr.slaveId ? second : first;
        if (master == null || slave == null) {
            throw new BizException("主档或原档已不存在，无法撤销");
        }
        if (!Dict.MEMBER_MERGED.equals(slave.status) || !master.id.equals(slave.mergedIntoId)) {
            throw new BizException("档案归属关系已变化（会员#" + slave.id + " 当前不属于主档#"
                    + master.id + "），无法撤销");
        }

        Map<String, Object> slaveSnap = readJson(mr.slaveSnapshot);
        String originPhone = str(slaveSnap.get("phone"));
        String originName = str(slaveSnap.get("name"));
        int originPoints = intVal(slaveSnap.get("points"));

        // 手机号恢复安全检查：主档后来改号占用、或被其他有效会员占用则拒绝
        if (originPhone != null && memberRepository
                .countByPhoneAndStatus(originPhone, Dict.MEMBER_ACTIVE) > 0) {
            throw new BizException("原手机号 " + originPhone
                    + " 现在已被有效会员占用，无法静默恢复归属，请先处理手机号再撤销");
        }

        // —— 还原归属：只迁回“原始属于从档”的流水/订单，主档合并后新赚部分不动 ——
        int restoredLedgers = ledgerRepository.restoreOwnerByOrigin(slave.id, master.id);
        int restoredOrders = orderRepository.restoreOwnerByOrigin(slave.id, master.id);
        contactRepository.restoreCurrent(slave.id, master.id);

        // 两边余额各自按流水合计重建（流水不复制，合计天然不重复）
        int masterSum = ledgerRepository.sumPoints(master.id);
        int slaveSum = ledgerRepository.sumPoints(slave.id);
        master.points = masterSum;
        master.level = Dict.decideMemberLevel(masterSum);
        memberRepository.save(master);

        slave.points = slaveSum;
        slave.level = Dict.decideMemberLevel(slaveSum);
        slave.name = originName != null ? originName : slave.name;
        slave.status = Dict.MEMBER_ACTIVE;
        slave.mergedIntoId = null;
        slave.mergedAt = null;
        slave.phone = originPhone;
        memberRepository.save(slave);

        // 手机号守卫恢复（同号合并时主档仍持有该号的情况不会发生：同号撤销前上面的占用检查会先拒绝；
        // 不同号时恢复原从档号码）
        if (originPhone != null && !originPhone.equals(master.phone)) {
            memberService.bindNewPhone(originPhone, slave.id);
        }

        ledgerService.appendMark(slave.id, slave.id, Dict.LEDGER_REVOKE_MARK, "REVOKE", mr.id,
                "撤销合并：从主档#" + master.id + " 还原原归属，恢复手机号 " + originPhone,
                operator.name);

        mr.status = Dict.MERGE_REVOKED;
        mr.revokeOperator = operator.name;
        mr.revokeReason = trim(req.reason, 255);
        mr.revokedAt = LocalDateTime.now();
        mr.revokeSummary = trim("还原订单 " + restoredOrders + " 笔、积分流水 " + restoredLedgers
                + " 条；主档余额重建为 " + masterSum + "，原档余额重建为 " + slaveSum
                + "（合并前快照为 " + originPoints + "），积分未重复计算", 1000);
        return requestRepository.save(mr);
    }

    // ============================ 冲突检查 ============================

    /**
     * 合并前检查两类冲突：
     * <ul>
     *   <li>手机号冲突：两边手机号相同（历史遗留重复绑定），必须显式选保留号；
     *       或某一方手机号被第三个有效会员占用。</li>
     *   <li>积分来源冲突：会员当前积分余额与其积分流水合计不一致，
     *       必须选择“信余额”还是“信流水”，不能静默丢弃任何一边。</li>
     * </ul>
     */
    private List<String> detectConflicts(Member master, Member slave, MemberMergeRequest existing) {
        List<String> types = new ArrayList<>();
        List<String> detail = new ArrayList<>();

        if (master != null && slave != null && master.phone.equals(slave.phone)) {
            types.add(Dict.CONFLICT_PHONE);
            detail.add("两边手机号相同（" + master.phone
                    + "），必须由负责人确认主档保留该号码，不能静默覆盖");
        }
        List<Long> pairIds = (master == null || slave == null)
                ? List.of() : List.of(master.id, slave.id);
        for (Member side : new Member[]{master, slave}) {
            if (side == null || side.phone == null) {
                continue;
            }
            // 排除本工单的两张档案后仍有人占用，才是真正的第三方手机号冲突
            if (!pairIds.isEmpty()
                    && memberRepository.existsPhoneConflictOutside(
                            side.phone, Dict.MEMBER_ACTIVE, pairIds)) {
                if (!types.contains(Dict.CONFLICT_PHONE)) {
                    types.add(Dict.CONFLICT_PHONE);
                }
                detail.add("手机号 " + side.phone + " 还被主档/重复档之外的第三个有效会员占用");
            }
        }
        if (slave != null) {
            int ledgerSum = ledgerRepository.sumPoints(slave.id);
            if (ledgerSum != slave.points) {
                types.add(Dict.CONFLICT_POINTS);
                detail.add("重复档积分来源对不上：当前余额 " + slave.points
                        + "，积分流水合计 " + ledgerSum + "，请选择以哪边为准");
            }
        }
        if (master != null) {
            int masterLedgerSum = ledgerRepository.sumPoints(master.id);
            if (masterLedgerSum != master.points) {
                types.add(Dict.CONFLICT_POINTS);
                detail.add("主档积分来源对不上：当前余额 " + master.points
                        + "，积分流水合计 " + masterLedgerSum + "，请选择以哪边为准");
            }
        }
        // 若已有处理决策，对应冲突视为已解决（执行期 detect 时不再报）
        if (existing != null) {
            if (existing.resolvedPhone != null && types.contains(Dict.CONFLICT_PHONE)) {
                types.remove(Dict.CONFLICT_PHONE);
                detail.removeIf(d -> d.contains("手机号"));
            }
            if (existing.pointsStrategy != null && types.contains(Dict.CONFLICT_POINTS)) {
                types.remove(Dict.CONFLICT_POINTS);
                detail.removeIf(d -> d.contains("积分"));
            }
        }
        if (types.isEmpty()) {
            return List.of();
        }
        List<String> encoded = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            String keyword = Dict.CONFLICT_PHONE.equals(type) ? "手机号" : "积分";
            encoded.add(type + "|" + detail.stream()
                    .filter(d -> d.contains(keyword))
                    .findFirst().orElse(type));
        }
        return encoded;
    }

    /**
     * 按已确认的积分来源策略调平某一边档案，使“流水合计 == 余额”恒成立；
     * 无策略或本就一致时不动。TRUST_BALANCE 保余额补流水；REBUILD_FROM_LEDGER 重建余额并留痕。
     */
    private void reconcilePoints(Member side, MemberMergeRequest mr,
                                 OperatorContext operator, String sideLabel) {
        int ledgerSum = ledgerRepository.sumPoints(side.id);
        if (ledgerSum == side.points) {
            return;
        }
        if (Dict.POINTS_STRATEGY_TRUST_BALANCE.equals(mr.pointsStrategy)) {
            ledgerService.appendReconciliation(side, side.points - ledgerSum,
                    "MERGE", mr.id, "合并前" + sideLabel + "积分来源调平（以当前余额为准）",
                    operator.name);
        } else if (Dict.POINTS_STRATEGY_REBUILD_LEDGER.equals(mr.pointsStrategy)) {
            int diff = ledgerSum - side.points;
            side.points = ledgerSum;
            memberRepository.save(side);
            ledgerService.appendMark(side.id, side.id, Dict.LEDGER_MANUAL, "MERGE", mr.id,
                    "合并前" + sideLabel + "积分来源重建（以流水合计为准，余额差异 " + diff + "）",
                    operator.name);
        }
    }

    private void applyConflict(MemberMergeRequest mr, List<String> encoded) {
        List<String> types = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (String e : encoded) {
            int sep = e.indexOf('|');
            types.add(sep > 0 ? e.substring(0, sep) : e);
            details.add(sep > 0 ? e.substring(sep + 1) : e);
        }
        mr.status = Dict.MERGE_CONFLICT;
        mr.conflictTypes = String.join(",", types);
        mr.conflictDetail = trim(String.join("；", details), 1000);
    }

    // ============================ DTO / 快照工具 ============================

    private MemberMergeDetail toDetail(MemberMergeRequest req) {
        MemberMergeDetail d = new MemberMergeDetail();
        d.id = req.id;
        d.masterId = req.masterId;
        d.slaveId = req.slaveId;
        d.status = req.status;
        d.reason = req.reason;
        d.operator = req.operator;
        d.conflictTypes = req.conflictTypes;
        d.conflictDetail = req.conflictDetail;
        d.resolvedPhone = req.resolvedPhone;
        d.pointsStrategy = req.pointsStrategy;
        d.mergeOperator = req.mergeOperator;
        d.mergedAt = fmt(req.mergedAt);
        d.revokeOperator = req.revokeOperator;
        d.revokeReason = req.revokeReason;
        d.revokedAt = fmt(req.revokedAt);
        d.resultSummary = req.resultSummary;
        d.revokeSummary = req.revokeSummary;
        d.createdAt = fmt(req.createdAt);
        d.version = req.version;
        d.masterSnapshot = req.masterSnapshot;
        d.slaveSnapshot = req.slaveSnapshot;

        d.master = profile(req.masterId);
        d.slave = profile(req.slaveId);
        return d;
    }

    private MemberMergeDetail.Profile profile(Long memberId) {
        Member m = memberRepository.findById(memberId).orElse(null);
        if (m == null) {
            return null;
        }
        MemberMergeDetail.Profile p = new MemberMergeDetail.Profile();
        p.id = m.id;
        p.phone = m.phone;
        p.name = m.name;
        p.points = m.points;
        p.level = m.level;
        p.status = m.status;
        p.version = m.version;
        p.mergedIntoId = m.mergedIntoId;
        p.orderCount = orderRepository.countByMemberId(m.id);
        p.ledgerCount = ledgerRepository.countByMemberId(m.id);
        p.ledgerSum = ledgerRepository.sumPoints(m.id);
        return p;
    }

    private String snapshot(Long id, String phone, String name, int points, String level) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("phone", phone);
        map.put("name", name);
        map.put("points", points);
        map.put("level", level);
        map.put("capturedAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"id\":" + id + "}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o == null ? 0 : Integer.parseInt(String.valueOf(o));
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static List<String> splitTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(csv.split(",")));
    }

    private Member lockMember(Long id) {
        return memberRepository.findByIdForUpdate(id).orElse(null);
    }

    private static void requireActive(Member m, String role) {
        if (m == null) {
            throw new BizException(role + "会员不存在");
        }
        if (!Dict.MEMBER_ACTIVE.equals(m.status)) {
            throw new BizException(role + "会员#" + m.id + " 已合并归档（只读别名），不能再参与合并");
        }
    }

    private static long min(long a, long b) {
        return Math.min(a, b);
    }

    private static long max(long a, long b) {
        return Math.max(a, b);
    }
}
