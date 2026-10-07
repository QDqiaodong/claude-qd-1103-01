package com.milktea.bar.dto;

/**
 * 会员手机号 / 姓名变更请求：每次变更必须填写原因，
 * 服务端保留前后值、原因、操作人与生效时间。
 */
public class ContactChangeRequest {

    public String phone;
    public String name;
    public String reason;
    public String operator;
    public String operatorRole;
    /** 页面加载会员时看到的版本号，服务端按最新版本校验，防止并发覆盖 */
    public Long expectedVersion;
}
