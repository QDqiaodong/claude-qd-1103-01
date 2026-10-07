package com.milktea.bar.dto;

import java.util.List;

/**
 * 会员档案详情：当前档案 + 合并归属追溯 + 历史订单（归主档后统一可见）
 * + 积分流水 + 联系方式变更审计。
 */
public class MemberProfileDetail {

    public Long id;
    public String phone;
    public String name;
    public Integer points;
    public String level;
    public String status;
    public Long version;
    public Long mergedIntoId;
    public String mergedAt;
    public String createdAt;

    /** 已合并档指向的主档（含主档联系方式），查询时可追溯 */
    public MasterRef master;

    public static class MasterRef {
        public Long id;
        public String phone;
        public String name;
    }

    /** 归到本档案名下的别名（被合并进来的只读原档） */
    public List<AliasRef> aliases;

    public static class AliasRef {
        public Long id;
        public String phone;
        public String name;
        public String mergedAt;
        public String reason;
        public String mergeOperator;
        public Long mergeRequestId;
    }

    public List<OrderRef> orders;

    public static class OrderRef {
        public Long id;
        public String payType;
        public String total;
        public String status;
        public Integer pointsEarned;
        public Long originMemberId;
        public String createdAt;
    }

    public List<LedgerRef> ledger;

    public static class LedgerRef {
        public Long id;
        public String changeType;
        public Integer pointsDelta;
        public Integer balanceAfter;
        public String refType;
        public Long refId;
        public String remark;
        public String operator;
        public Long originMemberId;
        public String createdAt;
    }

    public List<ContactChangeRef> contactChanges;

    public static class ContactChangeRef {
        public Long id;
        public String fieldName;
        public String beforeValue;
        public String afterValue;
        public String reason;
        public String operator;
        public String effectiveAt;
    }
}
