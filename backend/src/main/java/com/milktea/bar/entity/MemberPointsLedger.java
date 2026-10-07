package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会员积分流水：积分余额的唯一可信来源。
 *
 * <ul>
 *   <li>{@link #memberId}：当前归属会员（合并后迁移到主档，撤销时还原）；</li>
 *   <li>{@link #originMemberId}：积分最初产生于哪个档案，撤销合并时按它还原归属，
 *       迁移/还原只改归属不复制流水，因此积分绝不被重复计算；</li>
 *   <li>{@link #balanceAfter}：流水落到“当前归属档案”上的变动后余额。</li>
 * </ul>
 */
@Entity
@Table(name = "member_points_ledger")
public class MemberPointsLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    @Column(name = "origin_member_id", nullable = false)
    public Long originMemberId;

    /** OPENING / EARN / REFUND / MANUAL / MERGE_MARK / REVOKE_MARK */
    @Column(name = "change_type", nullable = false, length = 24)
    public String changeType;

    @Column(name = "points_delta", nullable = false)
    public Integer pointsDelta;

    @Column(name = "balance_after", nullable = false)
    public Integer balanceAfter;

    /** OPENING / ORDER / MANUAL / MERGE / REVOKE */
    @Column(name = "ref_type", length = 24)
    public String refType;

    @Column(name = "ref_id")
    public Long refId;

    @Column(length = 255)
    public String remark;

    @Column(length = 32)
    public String operator;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
}
