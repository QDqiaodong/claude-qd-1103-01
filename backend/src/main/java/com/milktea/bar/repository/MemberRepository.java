package com.milktea.bar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /** 仅统计有效会员的手机号占用（已合并档保留别名，不参与唯一性） */
    long countByPhoneAndStatus(String phone, String status);

    /** 除 excludeIds 之外，是否还有有效会员占用该手机号（合并冲突第三方占用检查） */
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(m) > 0 FROM Member m WHERE m.phone = :phone AND m.status = :status AND m.id NOT IN :excludeIds")
    boolean existsPhoneConflictOutside(@org.springframework.data.repository.query.Param("phone") String phone,
                                       @org.springframework.data.repository.query.Param("status") String status,
                                       @org.springframework.data.repository.query.Param("excludeIds") java.util.List<Long> excludeIds);

    List<Member> findByStatusOrderByIdAsc(String status);

    List<Member> findByMergedIntoId(Long mergedIntoId);

    /**
     * 会员写操作（积分/联系方式/合并）统一走写锁，配合 @Version 最新版本校验，
     * 保证并发合并、并发改手机号、重复合并都只能有一个事务推进。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
