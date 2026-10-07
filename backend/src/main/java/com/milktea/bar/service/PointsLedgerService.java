package com.milktea.bar.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.milktea.bar.common.Dict;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberPointsLedger;
import com.milktea.bar.repository.MemberPointsLedgerRepository;

/**
 * 积分流水记账：会员余额的每次变动都经此追加一条不可变流水。
 * 合并/撤销走批量归属迁移（{@link MemberPointsLedgerRepository#reassignOwnerByMember}），
 * 不在此记账，避免复制流水导致积分被重复计算。
 */
@Service
public class PointsLedgerService {

    private final MemberPointsLedgerRepository ledgerRepository;

    public PointsLedgerService(MemberPointsLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * 在已加锁的会员档案上追加流水并把余额设为变动后值。
     *
     * @return 变动后的余额
     */
    public int append(Member owner, String changeType, int delta, String refType, Long refId,
                      String remark, String operator) {
        return append(owner, owner.id, changeType, delta, refType, refId, remark, operator);
    }

    /**
     * 追加流水，可显式指定原始归属（合并后对从档历史订单退款时，
     * 退款流水的 origin 仍是原从档，撤销合并才能正确还原）。
     */
    public int append(Member owner, Long originMemberId, String changeType, int delta, String refType,
                      Long refId, String remark, String operator) {
        int balance = owner.points + delta;
        MemberPointsLedger row = new MemberPointsLedger();
        row.memberId = owner.id;
        row.originMemberId = originMemberId;
        row.changeType = changeType;
        row.pointsDelta = delta;
        row.balanceAfter = balance;
        row.refType = refType;
        row.refId = refId;
        row.remark = remark;
        row.operator = operator;
        row.createdAt = LocalDateTime.now();
        ledgerRepository.save(row);
        owner.points = balance;
        return balance;
    }

    /**
     * 追加一条 0 积分标记流水（合并/撤销审计锚点），不改余额。
     * originMemberId 显式指定，允许标记行记录在非原始产生方档案上。
     */
    public void appendMark(Long ownerId, Long originMemberId, String changeType, String refType,
                           Long refId, String remark, String operator) {
        MemberPointsLedger row = new MemberPointsLedger();
        row.memberId = ownerId;
        row.originMemberId = originMemberId;
        row.changeType = changeType;
        row.pointsDelta = 0;
        row.balanceAfter = 0;
        row.refType = refType;
        row.refId = refId;
        row.remark = remark;
        row.operator = operator;
        row.createdAt = LocalDateTime.now();
        ledgerRepository.save(row);
    }

    /**
     * 积分来源调平（合并冲突 TRUST_BALANCE）：余额以当前值为准且不变，
     * 仅补一条 delta 流水使“流水合计 == 当前余额”。
     * balance_after 记当前余额（不是 owner.points + delta），避免虚增余额。
     */
    public void appendReconciliation(Member owner, int delta, String refType, Long refId,
                                     String remark, String operator) {
        MemberPointsLedger row = new MemberPointsLedger();
        row.memberId = owner.id;
        row.originMemberId = owner.id;
        row.changeType = Dict.LEDGER_MANUAL;
        row.pointsDelta = delta;
        row.balanceAfter = owner.points;
        row.refType = refType;
        row.refId = refId;
        row.remark = remark;
        row.operator = operator;
        row.createdAt = LocalDateTime.now();
        ledgerRepository.save(row);
    }

    public int sumPoints(Long memberId) {
        return ledgerRepository.sumPoints(memberId);
    }
}
