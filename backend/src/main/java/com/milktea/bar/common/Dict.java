package com.milktea.bar.common;

import java.math.BigDecimal;
import java.util.List;

/**
 * 奶茶饮品店 · 原料库存与配方出单 业务词典与规则参数表。
 *
 * <p>集中存放状态集合、流程、阈值等规则常量，Service 层引用此处做校验与判定，
 * 便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 原料 ingredient ----------------
    /** 原料类别：茶底 / 奶 / 小料 / 糖浆 */
    public static final List<String> INGREDIENT_CATEGORIES = List.of("茶底", "奶", "小料", "糖浆");

    /** 原料状态：启用 / 停用（停用原料不能进配方） */
    public static final List<String> INGREDIENT_STATUS = List.of("启用", "停用");

    // ---------------- 会员 member ----------------
    public static final List<String> MEMBER_LEVELS = List.of("普通", "银卡", "金卡");

    /** 会员档案状态：有效 / 已合并（已合并档为只读别名，可追溯到主档） */
    public static final String MEMBER_ACTIVE = "有效";
    public static final String MEMBER_MERGED = "已合并";

    /** 操作角色：负责人才能发起撤销合并、解决冲突 */
    public static final String ROLE_STAFF = "店员";
    public static final String ROLE_MANAGER = "负责人";
    public static final List<String> OPERATOR_ROLES = List.of(ROLE_STAFF, ROLE_MANAGER);

    // ---------------- 会员合并工单 ----------------
    /** 待处理（店员已标记，等待预览后执行合并） */
    public static final String MERGE_PENDING = "PENDING";
    /** 冲突待处理（手机号冲突 / 积分来源对不上，必须显式解决） */
    public static final String MERGE_CONFLICT = "CONFLICT";
    /** 已合并 */
    public static final String MERGE_MERGED = "MERGED";
    /** 已撤销（仅负责人发起，积分不重复计算地还原归属） */
    public static final String MERGE_REVOKED = "REVOKED";
    public static final List<String> MERGE_STATUSES =
            List.of(MERGE_PENDING, MERGE_CONFLICT, MERGE_MERGED, MERGE_REVOKED);

    public static final String CONFLICT_PHONE = "PHONE_CONFLICT";
    public static final String CONFLICT_POINTS = "POINTS_SOURCE";

    /** 积分来源冲突解决策略：以当前余额为准补一条调平流水 / 以流水合计重建余额 */
    public static final String POINTS_STRATEGY_TRUST_BALANCE = "TRUST_BALANCE";
    public static final String POINTS_STRATEGY_REBUILD_LEDGER = "REBUILD_FROM_LEDGER";
    public static final List<String> POINTS_STRATEGIES =
            List.of(POINTS_STRATEGY_TRUST_BALANCE, POINTS_STRATEGY_REBUILD_LEDGER);

    // ---------------- 积分流水类型 ----------------
    public static final String LEDGER_OPENING = "OPENING";
    public static final String LEDGER_EARN = "EARN";
    public static final String LEDGER_REFUND = "REFUND";
    public static final String LEDGER_MANUAL = "MANUAL";
    public static final String LEDGER_MERGE_MARK = "MERGE_MARK";
    public static final String LEDGER_REVOKE_MARK = "REVOKE_MARK";

    // ---------------- 联系方式变更字段 ----------------
    public static final String FIELD_PHONE = "PHONE";
    public static final String FIELD_NAME = "NAME";

    /** 会员累计积分升级阈值（达到即升级） */
    public static final int SILVER_POINTS = 500;

    public static final int GOLD_POINTS = 2000;

    /** 消费 1 元累计的积分（出杯时按金额累加、退款时反向回退） */
    public static final int POINTS_PER_YUAN = 1;

    // ---------------- 配方 recipe ----------------
    /** 配方售价下限：必须大于该值（0.01 元） */
    public static final BigDecimal RECIPE_MIN_PRICE = new BigDecimal("0.01");

    /** 每出一杯固定消耗的茶底量（茶底用量未在配方中配置，按此统一口径扣减） */
    public static final int BASE_TEA_PER_CUP = 10;

    // ---------------- 出杯记录 cup_order ----------------
    /** 支付方式 */
    public static final List<String> PAY_TYPES = List.of("微信", "支付宝", "现金");

    /** 订单状态：已出杯 / 已退款 */
    public static final List<String> CUP_ORDER_STATUS = List.of("已出杯", "已退款");

    // ---------------- 取值方法 ----------------

    public static boolean isValidIngredientCategory(String category) {
        return category != null && INGREDIENT_CATEGORIES.contains(category);
    }

    public static boolean isValidIngredientStatus(String status) {
        return status != null && INGREDIENT_STATUS.contains(status);
    }

    public static boolean isValidMemberLevel(String level) {
        return level != null && MEMBER_LEVELS.contains(level);
    }

    public static boolean isValidMergeStatus(String status) {
        return status != null && MERGE_STATUSES.contains(status);
    }

    public static boolean isValidPointsStrategy(String strategy) {
        return strategy != null && POINTS_STRATEGIES.contains(strategy);
    }

    /** 手机号基本形态：11 位数字、1 开头（页面/接口层的轻校验，唯一性以服务端守卫为准） */
    public static boolean isValidPhone(String phone) {
        return phone != null && phone.matches("1\\d{10}");
    }

    /** 按累计积分自动判定会员等级 */
    public static String decideMemberLevel(int points) {
        if (points >= GOLD_POINTS) {
            return "金卡";
        }
        if (points >= SILVER_POINTS) {
            return "银卡";
        }
        return "普通";
    }

    public static boolean isValidPayType(String payType) {
        return payType != null && PAY_TYPES.contains(payType);
    }

    public static boolean isValidCupOrderStatus(String status) {
        return status != null && CUP_ORDER_STATUS.contains(status);
    }

    /** 校验小料份数必须为正整数 */
    public static boolean isValidToppingQty(Integer qty) {
        return qty != null && qty > 0;
    }
}
