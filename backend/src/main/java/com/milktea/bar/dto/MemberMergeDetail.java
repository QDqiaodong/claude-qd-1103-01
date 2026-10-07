package com.milktea.bar.dto;

import java.util.List;

/**
 * 合并工单详情 / 预览：两边档案对照、历史订单数与积分流水概览、
 * 冲突信息、合并前后差异（snapshots）与完整审计记录。
 */
public class MemberMergeDetail {

    public Long id;
    public Long masterId;
    public Long slaveId;
    public String status;
    public String reason;
    public String operator;
    public String conflictTypes;
    public String conflictDetail;
    public String resolvedPhone;
    public String pointsStrategy;
    public String mergeOperator;
    public String mergedAt;
    public String revokeOperator;
    public String revokeReason;
    public String revokedAt;
    public String resultSummary;
    public String revokeSummary;
    public String createdAt;
    public Long version;

    /** 合并前主档/从档 JSON 快照（手机号、姓名、积分、等级），详情页展示合并前后差异 */
    public String masterSnapshot;
    public String slaveSnapshot;

    public Profile master;
    public Profile slave;
    /** 执行合并前的预检冲突清单（空=可合并） */
    public List<String> precheckConflicts;

    public static class Profile {
        public Long id;
        public String phone;
        public String name;
        public Integer points;
        public String level;
        public String status;
        public Long version;
        public Long mergedIntoId;
        public long orderCount;
        public long ledgerCount;
        public int ledgerSum;
    }
}
