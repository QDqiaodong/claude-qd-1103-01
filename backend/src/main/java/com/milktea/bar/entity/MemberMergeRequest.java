package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 会员档案合并工单。
 *
 * <p>状态机：PENDING（待处理）→ MERGED（已合并）→ REVOKED（已撤销，仅负责人发起）；
 * 标记/执行阶段发现手机号冲突或积分来源对不上时进入 CONFLICT（冲突待处理），
 * 冲突必须显式解决后才能继续，绝不静默覆盖任何一边的手机号或积分。</p>
 *
 * <p>master_snapshot / slave_snapshot 为合并前两边档案 JSON 快照，
 * 与积分流水、联系方式变更日志共同构成完整审计链路。</p>
 */
@Entity
@Table(name = "member_merge_request")
public class MemberMergeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "master_id", nullable = false)
    public Long masterId;

    @Column(name = "slave_id", nullable = false)
    public Long slaveId;

    /** PENDING / CONFLICT / MERGED / REVOKED */
    @Column(nullable = false, length = 24)
    public String status;

    @Column(nullable = false, length = 255)
    public String reason;

    @Column(nullable = false, length = 32)
    public String operator;

    @Column(name = "conflict_types", length = 255)
    public String conflictTypes;

    @Column(name = "conflict_detail", length = 1000)
    public String conflictDetail;

    @Column(name = "resolved_phone", length = 24)
    public String resolvedPhone;

    @Column(name = "points_strategy", length = 32)
    public String pointsStrategy;

    @Column(name = "merge_operator", length = 32)
    public String mergeOperator;

    @Column(name = "merged_at")
    public LocalDateTime mergedAt;

    @Column(name = "revoke_operator", length = 32)
    public String revokeOperator;

    @Column(name = "revoke_reason", length = 255)
    public String revokeReason;

    @Column(name = "revoked_at")
    public LocalDateTime revokedAt;

    @Lob
    @Column(name = "master_snapshot")
    public String masterSnapshot;

    @Lob
    @Column(name = "slave_snapshot")
    public String slaveSnapshot;

    @Column(name = "result_summary", length = 1000)
    public String resultSummary;

    @Column(name = "revoke_summary", length = 1000)
    public String revokeSummary;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Version
    public Long version;

    /**
     * 开放工单去重键：PENDING/CONFLICT 时为「masterId-slaveId」，
     * MERGED/REVOKED 时为 null。唯一索引允许重复 NULL，
     * 既保证同一对档案只有一张开放工单，又不阻断合并/撤销的完整生命周期。
     */
    @Column(name = "open_key", length = 64, unique = true)
    public String openKey;
}
