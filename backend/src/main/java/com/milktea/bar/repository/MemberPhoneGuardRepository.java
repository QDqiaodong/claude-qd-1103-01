package com.milktea.bar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberPhoneGuard;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface MemberPhoneGuardRepository extends JpaRepository<MemberPhoneGuard, String> {

    /**
     * 对手机号守卫行加写锁。号码尚不存在（INSERT 与 INSERT 之间靠主键唯一约束串行，
     * 冲突事务收到 DuplicateKey 后稳定失败），存在则后到事务阻塞等待先行事务提交，
     * 从而同一手机号不可能被两个事务同时绑定给两个有效会员。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM MemberPhoneGuard g WHERE g.phone = :phone")
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<MemberPhoneGuard> findByPhoneForUpdate(@Param("phone") String phone);

    @Modifying
    @Query("UPDATE MemberPhoneGuard g SET g.memberId = :memberId, g.updatedAt = CURRENT_TIMESTAMP WHERE g.phone = :phone")
    int rebind(@Param("phone") String phone, @Param("memberId") Long memberId);

    @Modifying
    @Query("DELETE FROM MemberPhoneGuard g WHERE g.phone IN :phones")
    void deleteByPhones(@Param("phones") List<String> phones);
}
