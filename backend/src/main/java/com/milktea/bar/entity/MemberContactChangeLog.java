package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会员联系方式变更审计：手机号或姓名的每一次修改都保留
 * 变更前后值、原因、操作人与生效时间，只追加、不覆盖。
 */
@Entity
@Table(name = "member_contact_change_log")
public class MemberContactChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    @Column(name = "current_member_id")
    public Long currentMemberId;

    /** PHONE / NAME */
    @Column(name = "field_name", nullable = false, length = 16)
    public String fieldName;

    @Column(name = "before_value", length = 64)
    public String beforeValue;

    @Column(name = "after_value", length = 64)
    public String afterValue;

    @Column(nullable = false, length = 255)
    public String reason;

    @Column(nullable = false, length = 32)
    public String operator;

    @Column(name = "effective_at", nullable = false)
    public LocalDateTime effectiveAt;
}
