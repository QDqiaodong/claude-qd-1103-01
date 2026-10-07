package com.milktea.bar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 会员档案。
 *
 * <p>有效会员（status=有效）手机号全局唯一，唯一性由 member_phone_guard 行锁 +
 * 服务端最新版本校验共同保证；已合并档（status=已合并）保留只读别名、合并原因
 * （在合并工单上）、操作人与时间，{@link #mergedIntoId} 指向主档，查询时可追溯。</p>
 *
 * <p>{@link #version} 为 JPA 乐观锁版本，所有会员写操作都以最新版本校验，
 * 配合关键路径的悲观行锁，重复合并 / 并发合并 / 并发改手机号都得到稳定结果。</p>
 */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 24)
    public String phone;

    @Column(nullable = false, length = 32)
    public String name;

    @Column(nullable = false)
    public Integer points;

    @Column(nullable = false, length = 16)
    public String level;

    /** 有效 / 已合并 */
    @Column(nullable = false, length = 16)
    public String status;

    @Version
    public Long version;

    @Column(name = "merged_into_id")
    public Long mergedIntoId;

    @Column(name = "merged_at")
    public LocalDateTime mergedAt;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
