package com.milktea.bar.dto;

/**
 * 历史档案导入：用于把线下遗留的重复档案（可能同手机号、积分与流水对不上）
 * 登记进系统等待合并。此入口允许破坏“有效手机号唯一”的在线不变量，
 * 仅负责人可用，并且导入后该档案会立刻出现在合并冲突待处理中，
 * 普通建会员 / 改手机号入口永远不允许同号。
 */
public class LegacyImportRequest {

    public String phone;
    public String name;
    public Integer points;
    public String reason;
    public String operator;
    public String operatorRole;
}
