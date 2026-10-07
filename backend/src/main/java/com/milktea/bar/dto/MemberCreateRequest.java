package com.milktea.bar.dto;

/**
 * 新建会员请求：联系方式与开档积分 + 必填操作人。
 */
public class MemberCreateRequest {

    public String phone;
    public String name;
    public Integer points;
    public String level;
    public String operator;
    public String operatorRole;
}
