package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberPointsLedger;

public interface MemberPointsLedgerRepository extends JpaRepository<MemberPointsLedger, Long> {

    List<MemberPointsLedger> findByMemberIdOrderByIdAsc(Long memberId);

    /** 合并：把从档名下全部流水（含标记行）归属迁到主档，只迁不复制，积分不会重复计算 */
    @Modifying
    @Query("UPDATE MemberPointsLedger l SET l.memberId = :masterId WHERE l.memberId = :slaveId")
    int reassignOwnerByMember(@Param("slaveId") Long slaveId, @Param("masterId") Long masterId);

    /** 撤销：把原属于某档案的流水还原回该档案（合并后由主档新赚的流水 origin 不同，不会被带走） */
    @Modifying
    @Query("UPDATE MemberPointsLedger l SET l.memberId = :originId WHERE l.originMemberId = :originId AND l.memberId = :masterId")
    int restoreOwnerByOrigin(@Param("originId") Long originId, @Param("masterId") Long masterId);

    @Query("SELECT COALESCE(SUM(l.pointsDelta), 0) FROM MemberPointsLedger l WHERE l.memberId = :memberId")
    int sumPoints(@Param("memberId") Long memberId);

    long countByMemberId(Long memberId);
}
