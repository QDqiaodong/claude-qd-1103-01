package com.milktea.bar.dto;

/**
 * 会员积分手工调整请求（消费/退款由出单链路自动记账，此入口供人工纠正）。
 */
public class PointsAdjustRequest {

    public Integer delta;
    public String reason;
    public String operator;
    public String operatorRole;
    public Long expectedVersion;
}
