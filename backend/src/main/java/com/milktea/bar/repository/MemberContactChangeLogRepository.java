package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.milktea.bar.entity.MemberContactChangeLog;

public interface MemberContactChangeLogRepository extends JpaRepository<MemberContactChangeLog, Long> {

    /** 某档案自己的变更记录（从档被合并后仍用原 member_id 查询） */
    List<MemberContactChangeLog> findByMemberIdOrderByIdAsc(Long memberId);

    /** 合并后：从档历史变更可经 current_member_id 在主档视图中一并追溯 */
    List<MemberContactChangeLog> findByCurrentMemberIdOrderByIdAsc(Long currentMemberId);

    @Modifying
    @Query("UPDATE MemberContactChangeLog c SET c.currentMemberId = :masterId WHERE c.memberId = :slaveId")
    int pointCurrentToMaster(@Param("slaveId") Long slaveId, @Param("masterId") Long masterId);

    @Modifying
    @Query("UPDATE MemberContactChangeLog c SET c.currentMemberId = :slaveId WHERE c.memberId = :slaveId AND c.currentMemberId = :masterId")
    int restoreCurrent(@Param("slaveId") Long slaveId, @Param("masterId") Long masterId);
}
