package com.milktea.bar.dto;

/**
 * 执行合并请求：必须带工单 id 与操作人；expectedVersion 为页面预览时看到的工单版本，
 * 服务端以最新版本复核，期间工单/档案被别人动过则稳定拒绝（幂等重复提交得到相同结果）。
 */
public class MergeExecuteRequest {

    public Long expectedVersion;
    public String operator;
    public String operatorRole;
}
