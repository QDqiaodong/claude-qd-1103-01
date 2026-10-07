package com.milktea.bar.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.dto.ContactChangeRequest;
import com.milktea.bar.dto.LegacyImportRequest;
import com.milktea.bar.dto.PointsAdjustRequest;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberContactChangeLog;
import com.milktea.bar.entity.MemberPhoneGuard;
import com.milktea.bar.repository.MemberContactChangeLogRepository;
import com.milktea.bar.repository.MemberPhoneGuardRepository;
import com.milktea.bar.repository.MemberRepository;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberPhoneGuardRepository guardRepository;
    private final MemberContactChangeLogRepository contactRepository;
    private final PointsLedgerService ledgerService;

    public MemberService(MemberRepository memberRepository,
                         MemberPhoneGuardRepository guardRepository,
                         MemberContactChangeLogRepository contactRepository,
                         PointsLedgerService ledgerService) {
        this.memberRepository = memberRepository;
        this.guardRepository = guardRepository;
        this.contactRepository = contactRepository;
        this.ledgerService = ledgerService;
    }

    // ============================ 查询 ============================

    public List<Member> list(String level, String status) {
        return memberRepository.findAll().stream()
                .filter(m -> level == null || level.equals(m.level))
                .filter(m -> status == null || status.equals(m.status))
                .toList();
    }

    // ============================ 新建会员 ============================

    /**
     * 新建有效会员（单事务）：对手机号守卫行加锁（号码不存在时插入守卫，
     * 并发同号插入由主键唯一约束稳定挡住一个），随后建档案与开档积分流水。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member create(Member input, String operatorName, String operatorRole) {
        OperatorContext operator = OperatorContext.of(operatorName, operatorRole);
        validateContact(input.phone, input.name);
        int points = input.points == null ? 0 : input.points;
        if (points < 0) {
            throw new BizException("积分不能为负");
        }
        String level = (input.level == null) ? "普通" : input.level;
        if (!Dict.isValidMemberLevel(level)) {
            throw new BizException("会员等级必须是：普通 / 银卡 / 金卡");
        }

        // 先落会员行拿到 id（守卫必须指向具体会员）；并发同号时后到事务在守卫
        // 插入阶段被唯一主键挡住并整体回滚，会员行也不会残留
        Member saved = new Member();
        saved.phone = input.phone;
        saved.name = input.name;
        saved.points = 0;
        saved.level = level;
        saved.status = Dict.MEMBER_ACTIVE;
        saved.createdAt = LocalDateTime.now();
        saved = memberRepository.saveAndFlush(saved);

        bindNewPhone(input.phone, saved.id);

        if (points > 0) {
            // 开档积分以 OPENING 流水入账，余额永远可由流水合计重建
            ledgerService.append(saved, Dict.LEDGER_OPENING, points, "OPENING", null,
                    "开档累计积分", operator.name);
            saved.level = level;
            memberRepository.save(saved);
        }
        return saved;
    }

    /**
     * 仅允许改等级。手机号 / 姓名必须走联系方式变更审计入口，
     * 积分调整必须走带流水的积分入口。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member update(Long id, Member input) {
        Member existing = memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：" + id));
        if (input.phone != null && !input.phone.equals(existing.phone)) {
            throw new BizException("手机号变更必须走「联系方式变更」入口并填写原因，已为你保留审计记录");
        }
        if (input.name != null && !input.name.equals(existing.name)) {
            throw new BizException("姓名变更必须走「联系方式变更」入口并填写原因");
        }
        if (input.points != null && !input.points.equals(existing.points)) {
            throw new BizException("积分调整必须走「调整积分」入口并填写原因");
        }
        if (input.level != null) {
            if (!Dict.isValidMemberLevel(input.level)) {
                throw new BizException("会员等级必须是：普通 / 银卡 / 金卡");
            }
            existing.level = input.level;
        }
        return memberRepository.save(existing);
    }

    // ============================ 联系方式变更（带审计） ============================

    /**
     * 修改手机号 / 姓名（单事务）：
     * <ol>
     *   <li>对会员行加悲观写锁并校验 expectedVersion（并发改手机号只能有一个成功）；</li>
     *   <li>手机号变化时按号码升序对守卫行加锁，确认新号未被别的有效会员占用，
     *       旧号释放、新号绑定 —— 同一手机号不可能同时绑定两个有效会员；</li>
     *   <li>逐字段追加联系方式变更日志（前后值/原因/操作人/生效时间）。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public Member changeContact(Long id, ContactChangeRequest req) {
        if (req == null) {
            throw new BizException("变更内容不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);
        if (req.reason == null || req.reason.isBlank()) {
            throw new BizException("联系方式变更必须填写原因");
        }
        Member member = memberRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("会员不存在：" + id));
        requireActive(member);
        checkVersion(member, req.expectedVersion);

        boolean phoneChanged = req.phone != null && !req.phone.equals(member.phone);
        boolean nameChanged = req.name != null && !req.name.equals(member.name);
        if (!phoneChanged && !nameChanged) {
            throw new BizException("手机号或姓名没有变化，无需变更");
        }
        if (req.phone != null) {
            validateContact(req.phone, req.name != null ? req.name : member.name);
        }
        if (req.name != null && req.name.isBlank()) {
            throw new BizException("昵称不能为空");
        }

        LocalDateTime effectiveAt = LocalDateTime.now();
        if (phoneChanged) {
            appendContactLog(member.id, member.id, Dict.FIELD_PHONE,
                    member.phone, req.phone, req.reason, operator.name, effectiveAt);
            // 先锁新号再释放旧号：新号被占直接拒绝，旧号在事务提交后才真正释放
            bindNewPhone(req.phone, member.id);
            releasePhone(member.phone, member.id);
            member.phone = req.phone;
        }
        if (nameChanged) {
            appendContactLog(member.id, member.id, Dict.FIELD_NAME,
                    member.name, req.name, req.reason, operator.name, effectiveAt);
            member.name = req.name;
        }
        return memberRepository.save(member);
    }

    private void appendContactLog(Long memberId, Long currentMemberId, String field,
                                  String before, String after, String reason,
                                  String operator, LocalDateTime effectiveAt) {
        MemberContactChangeLog log = new MemberContactChangeLog();
        log.memberId = memberId;
        log.currentMemberId = currentMemberId;
        log.fieldName = field;
        log.beforeValue = before;
        log.afterValue = after;
        log.reason = trimReason(reason);
        log.operator = operator;
        log.effectiveAt = effectiveAt;
        contactRepository.save(log);
    }

    private static String trimReason(String reason) {
        String r = reason.trim();
        return r.length() > 255 ? r.substring(0, 255) : r;
    }

    // ============================ 积分手工调整（带流水） ============================

    @Transactional(rollbackFor = Exception.class)
    public Member adjustPoints(Long id, PointsAdjustRequest req) {
        if (req == null || req.delta == null) {
            throw new BizException("积分变动值不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);
        if (req.reason == null || req.reason.isBlank()) {
            throw new BizException("积分调整必须填写原因");
        }
        Member member = memberRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("会员不存在：" + id));
        requireActive(member);
        checkVersion(member, req.expectedVersion);

        int newPoints = member.points + req.delta;
        if (newPoints < 0) {
            throw new BizException("积分调整后不能为负（当前 " + member.points + "，本次变动 " + req.delta + "）");
        }
        ledgerService.append(member, Dict.LEDGER_MANUAL, req.delta, "MANUAL", null,
                "人工调整：" + req.reason, operator.name);
        member.level = Dict.decideMemberLevel(member.points);
        return memberRepository.save(member);
    }

    // ============================ 历史重复档导入（负责人） ============================

    /**
     * 负责人导入线下遗留档案：允许与现有有效会员同号（在线入口永不允许），
     * 导入后靠合并工单的冲突待处理流程消重。若该号尚无守卫，守卫指向导入档。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member importLegacy(LegacyImportRequest req) {
        if (req == null) {
            throw new BizException("导入内容不能为空");
        }
        OperatorContext operator = OperatorContext.of(req.operator, req.operatorRole);
        operator.requireManager("历史重复档案导入");
        validateContact(req.phone, req.name);
        if (req.reason == null || req.reason.isBlank()) {
            throw new BizException("历史档案导入必须填写来源/原因");
        }
        int points = req.points == null ? 0 : req.points;
        if (points < 0) {
            throw new BizException("积分不能为负");
        }

        Member member = new Member();
        member.phone = req.phone;
        member.name = req.name;
        member.points = 0;
        member.level = Dict.decideMemberLevel(points);
        member.status = Dict.MEMBER_ACTIVE;
        member.createdAt = LocalDateTime.now();
        member = memberRepository.save(member);

        // 守卫已指向别人即保持现状（同号重复正是待合并冲突）；否则绑定到导入档
        try {
            bindNewPhoneIfAbsent(req.phone, member.id);
        } catch (DataIntegrityViolationException dup) {
            // 并发导入/建会员：另一个事务已绑定同号，保持重复待合并状态即可
        }
        if (points > 0) {
            ledgerService.append(member, Dict.LEDGER_OPENING, points, "OPENING", null,
                    "历史档案导入：" + req.reason, operator.name);
        }
        return memberRepository.save(member);
    }

    // ============================ 手机号守卫（供合并服务复用） ============================

    /**
     * 绑定一个“尚未被任何有效会员占用”的手机号给 owner。
     * 必须在事务内调用；调用方应已持有相关会员行锁。
     */
    public void bindNewPhone(String phone, Long ownerId) {
        MemberPhoneGuard guard = lockGuard(phone);
        if (guard != null && (ownerId == null || !guard.memberId.equals(ownerId))) {
            throw new BizException("手机号已被其他有效会员绑定：" + phone + "（会员#" + guard.memberId + "），不能重复绑定");
        }
        LocalDateTime now = LocalDateTime.now();
        if (guard == null) {
            MemberPhoneGuard row = new MemberPhoneGuard();
            row.phone = phone;
            row.memberId = ownerId;
            row.updatedAt = now;
            try {
                guardRepository.saveAndFlush(row);
            } catch (DataIntegrityViolationException dup) {
                // 并发首绑同一新号：行锁等不到（行尚不存在），由主键唯一约束挡住后来者
                throw new BizException("手机号刚被其他操作绑定：" + phone + "，请刷新后重试");
            }
        } else if (ownerId != null) {
            guardRepository.rebind(phone, ownerId);
        }
    }

    /** 仅当号码无守卫时绑定（历史导入用），已被占用不报错。 */
    public void bindNewPhoneIfAbsent(String phone, Long ownerId) {
        MemberPhoneGuard guard = lockGuard(phone);
        if (guard == null) {
            MemberPhoneGuard row = new MemberPhoneGuard();
            row.phone = phone;
            row.memberId = ownerId;
            row.updatedAt = LocalDateTime.now();
            guardRepository.saveAndFlush(row);
        }
    }

    /** 仅对手机号守卫行加锁（不改绑），合并事务按号码升序调用以统一加锁顺序。 */
    public void touchGuard(String phone) {
        lockGuard(phone);
    }

    /** 把守卫号码改绑到指定会员（冲突解决时选定保留号）。 */
    public void rebindPhone(String phone, Long ownerId) {
        MemberPhoneGuard guard = lockGuard(phone);
        if (guard == null) {
            bindNewPhone(phone, ownerId);
        } else if (!guard.memberId.equals(ownerId)) {
            guardRepository.rebind(phone, ownerId);
        }
    }

    /** 释放旧号守卫（仅当当前归属确实是 ownerId，避免误删别人的绑定）。 */
    public void releasePhone(String phone, Long ownerId) {
        MemberPhoneGuard guard = lockGuard(phone);
        if (guard != null && guard.memberId.equals(ownerId)) {
            guardRepository.delete(guard);
            guardRepository.flush();
        }
    }

    private MemberPhoneGuard lockGuard(String phone) {
        return guardRepository.findByPhoneForUpdate(phone).orElse(null);
    }

    // ---------------- 校验工具 ----------------

    static void validateContact(String phone, String name) {
        if (!Dict.isValidPhone(phone)) {
            throw new BizException("手机号格式不正确（需为 1 开头的 11 位数字）");
        }
        if (name == null || name.isBlank()) {
            throw new BizException("昵称不能为空");
        }
        if (name.length() > 32) {
            throw new BizException("昵称过长");
        }
    }

    static void requireActive(Member member) {
        if (Dict.MEMBER_MERGED.equals(member.status)) {
            Long master = member.mergedIntoId;
            throw new BizException("会员#" + member.id + " 已合并归档（只读别名），请在主档#"
                    + master + " 上操作");
        }
    }

    static void checkVersion(Member member, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(member.version)) {
            throw new BizException("该档案刚被其他人修改过（页面版本 " + expectedVersion
                    + "，最新版本 " + member.version + "），请刷新后重试，禁止覆盖最新数据");
        }
    }
}
