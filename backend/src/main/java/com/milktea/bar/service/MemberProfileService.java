package com.milktea.bar.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.dto.MemberProfileDetail;
import com.milktea.bar.entity.CupOrder;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberContactChangeLog;
import com.milktea.bar.entity.MemberPointsLedger;
import com.milktea.bar.repository.CupOrderRepository;
import com.milktea.bar.repository.MemberContactChangeLogRepository;
import com.milktea.bar.repository.MemberMergeRequestRepository;
import com.milktea.bar.repository.MemberPointsLedgerRepository;
import com.milktea.bar.repository.MemberRepository;

/**
 * 会员档案只读详情：历史订单与积分流水在合并后统一归主档查询，
 * 已合并别名可追溯到主档，主档可看到所有别名及各自的联系方式变更审计。
 */
@Service
public class MemberProfileService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MemberRepository memberRepository;
    private final CupOrderRepository orderRepository;
    private final MemberPointsLedgerRepository ledgerRepository;
    private final MemberContactChangeLogRepository contactRepository;
    private final MemberMergeRequestRepository mergeRepository;

    public MemberProfileService(MemberRepository memberRepository,
                                CupOrderRepository orderRepository,
                                MemberPointsLedgerRepository ledgerRepository,
                                MemberContactChangeLogRepository contactRepository,
                                MemberMergeRequestRepository mergeRepository) {
        this.memberRepository = memberRepository;
        this.orderRepository = orderRepository;
        this.ledgerRepository = ledgerRepository;
        this.contactRepository = contactRepository;
        this.mergeRepository = mergeRepository;
    }

    @Transactional(readOnly = true)
    public MemberProfileDetail detail(Long id) {
        Member m = memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：" + id));
        MemberProfileDetail d = new MemberProfileDetail();
        d.id = m.id;
        d.phone = m.phone;
        d.name = m.name;
        d.points = m.points;
        d.level = m.level;
        d.status = m.status;
        d.version = m.version;
        d.mergedIntoId = m.mergedIntoId;
        d.mergedAt = m.mergedAt == null ? null : FMT.format(m.mergedAt);
        d.createdAt = m.createdAt == null ? null : FMT.format(m.createdAt);

        // 已合并档：追溯到主档，并以主档视角展示统一后的历史
        Long viewId = m.id;
        if (m.mergedIntoId != null) {
            viewId = m.mergedIntoId;
            Member master = memberRepository.findById(m.mergedIntoId).orElse(null);
            if (master != null) {
                d.master = new MemberProfileDetail.MasterRef();
                d.master.id = master.id;
                d.master.phone = master.phone;
                d.master.name = master.name;
            }
        }

        // 别名列表：当前视角档案作为主档吸收过的所有已合并原档
        d.aliases = new ArrayList<>();
        for (Member alias : memberRepository.findByMergedIntoId(viewId)) {
            MemberProfileDetail.AliasRef a = new MemberProfileDetail.AliasRef();
            a.id = alias.id;
            a.phone = alias.phone;
            a.name = alias.name;
            a.mergedAt = alias.mergedAt == null ? null : FMT.format(alias.mergedAt);
            mergeRepository.findBySlaveIdOrderByIdDesc(alias.id).stream()
                    .filter(r -> Dict.MERGE_MERGED.equals(r.status) || Dict.MERGE_REVOKED.equals(r.status))
                    .findFirst()
                    .ifPresent(r -> {
                        a.reason = r.reason;
                        a.mergeOperator = r.mergeOperator;
                        a.mergeRequestId = r.id;
                    });
            d.aliases.add(a);
        }

        d.orders = new ArrayList<>();
        for (CupOrder o : orderRepository.findByMemberId(viewId)) {
            MemberProfileDetail.OrderRef or = new MemberProfileDetail.OrderRef();
            or.id = o.id;
            or.payType = o.payType;
            or.total = o.total.toPlainString();
            or.status = o.status;
            or.pointsEarned = o.pointsEarned;
            or.originMemberId = o.originMemberId;
            or.createdAt = o.createdAt == null ? null : FMT.format(o.createdAt);
            d.orders.add(or);
        }

        d.ledger = new ArrayList<>();
        for (MemberPointsLedger l : ledgerRepository.findByMemberIdOrderByIdAsc(viewId)) {
            MemberProfileDetail.LedgerRef lr = new MemberProfileDetail.LedgerRef();
            lr.id = l.id;
            lr.changeType = l.changeType;
            lr.pointsDelta = l.pointsDelta;
            lr.balanceAfter = l.balanceAfter;
            lr.refType = l.refType;
            lr.refId = l.refId;
            lr.remark = l.remark;
            lr.operator = l.operator;
            lr.originMemberId = l.originMemberId;
            lr.createdAt = FMT.format(l.createdAt);
            d.ledger.add(lr);
        }

        // 联系方式审计：视角主档自身记录 + 归属指向主档的别名记录（按时间合并）
        List<MemberContactChangeLog> logs = new ArrayList<>(contactRepository.findByMemberIdOrderByIdAsc(viewId));
        logs.addAll(contactRepository.findByCurrentMemberIdOrderByIdAsc(viewId));
        logs.sort((a, b) -> Long.compare(a.id, b.id));
        d.contactChanges = new ArrayList<>();
        for (MemberContactChangeLog c : logs) {
            MemberProfileDetail.ContactChangeRef cr = new MemberProfileDetail.ContactChangeRef();
            cr.id = c.id;
            cr.fieldName = c.fieldName;
            cr.beforeValue = c.beforeValue;
            cr.afterValue = c.afterValue;
            cr.reason = c.reason;
            cr.operator = c.operator;
            cr.effectiveAt = FMT.format(c.effectiveAt);
            d.contactChanges.add(cr);
        }
        return d;
    }
}
