package com.milktea.bar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberMergeRequest;

import jakarta.persistence.LockModeType;

public interface MemberMergeRequestRepository extends JpaRepository<MemberMergeRequest, Long> {

    List<MemberMergeRequest> findByStatusOrderByIdDesc(String status);

    List<MemberMergeRequest> findAllByOrderByIdDesc();

    /** 同一对档案的开放工单（待处理/冲突）只允许存在一张，防止重复标记重复合并 */
    Optional<MemberMergeRequest> findFirstByMasterIdAndSlaveIdAndStatusIn(
            Long masterId, Long slaveId, List<String> statuses);

    /** 从档是否已被任意开放工单占用（同档不能同时并进两个主档） */
    @Query("SELECT r FROM MemberMergeRequest r WHERE r.slaveId = :slaveId AND r.status IN :statuses")
    List<MemberMergeRequest> findOpenBySlave(@Param("slaveId") Long slaveId,
                                             @Param("statuses") List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM MemberMergeRequest r WHERE r.id = :id")
    Optional<MemberMergeRequest> findByIdForUpdate(@Param("id") Long id);

    /** 某档案作为从档的全部工单（别名详情展示合并原因/操作人用），按时间倒序 */
    List<MemberMergeRequest> findBySlaveIdOrderByIdDesc(Long slaveId);
}
