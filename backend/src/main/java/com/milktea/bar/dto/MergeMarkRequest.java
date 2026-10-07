package com.milktea.bar.dto;

/**
 * 标记重复会员为待合并的请求。
 */
public class MergeMarkRequest {

    public Long masterId;
    public Long slaveId;
    public String reason;
    public String operator;
    /** 店员 / 负责人 */
    public String operatorRole;
}
