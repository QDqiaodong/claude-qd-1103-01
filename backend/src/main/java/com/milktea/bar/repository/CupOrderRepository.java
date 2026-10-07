package com.milktea.bar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.CupOrder;

import jakarta.persistence.LockModeType;

public interface CupOrderRepository extends JpaRepository<CupOrder, Long> {

    List<CupOrder> findByStatusOrderByCreatedAtDescIdDesc(String status);

    List<CupOrder> findAllByOrderByCreatedAtDescIdDesc();

    List<CupOrder> findByMemberId(Long memberId);

    long countByMemberId(Long memberId);

    /** 合并：历史订单归属主档（origin_member_id 保留，撤销可还原） */
    @Modifying
    @Query("UPDATE CupOrder o SET o.memberId = :masterId WHERE o.memberId = :slaveId")
    int reassignOwner(@Param("slaveId") Long slaveId, @Param("masterId") Long masterId);

    /** 撤销：仅还原原始就属于从档、且合并后未退款的订单（中间状态安全还原） */
    @Modifying
    @Query("UPDATE CupOrder o SET o.memberId = :originId WHERE o.originMemberId = :originId AND o.memberId = :masterId")
    int restoreOwnerByOrigin(@Param("originId") Long originId, @Param("masterId") Long masterId);

    /** 退款时对订单行加写锁，重复/并发退款只能有一个事务进入“已出杯”分支 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CupOrder o WHERE o.id = :id")
    Optional<CupOrder> findByIdForUpdate(@Param("id") Long id);
}
