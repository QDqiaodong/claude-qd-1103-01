package com.milktea.bar.dto;

/**
 * 撤销合并请求：仅负责人可发起；服务端校验主档手机号未被后续变更占用，
 * 并以不复制流水的方式还原历史订单与积分归属。
 */
public class MergeRevokeRequest {

    public Long expectedVersion;
    public String reason;
    public String operator;
    public String operatorRole;
}
