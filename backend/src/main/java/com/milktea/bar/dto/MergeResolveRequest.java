package com.milktea.bar.dto;

/**
 * 冲突待处理的解决请求（仅负责人）。
 *
 * <p>keepPhone：手机号冲突时主档最终保留的号码（必须是两边号码之一）；
 * pointsStrategy：积分来源冲突的处理策略；reason：解决说明。</p>
 */
public class MergeResolveRequest {

    public String keepPhone;
    public String pointsStrategy;
    public String reason;
    public String operator;
    public String operatorRole;
}
