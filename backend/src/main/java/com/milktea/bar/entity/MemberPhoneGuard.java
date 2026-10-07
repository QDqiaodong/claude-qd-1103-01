package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 有效手机号守卫：一个有效手机号一行。
 *
 * <p>建会员 / 改手机号 / 合并冲突处理涉及号码变更时，先对相关号码行
 * SELECT ... FOR UPDATE（按号码升序加锁），再决定绑定归属，
 * 从根上串行化“同一手机号绑定两个有效会员”。</p>
 */
@Entity
@Table(name = "member_phone_guard")
public class MemberPhoneGuard {

    @Id
    @Column(length = 24)
    public String phone;

    @Column(name = "member_id", nullable = false)
    public Long memberId;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;
}
