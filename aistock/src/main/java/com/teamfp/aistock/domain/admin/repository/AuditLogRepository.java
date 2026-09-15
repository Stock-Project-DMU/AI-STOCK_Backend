package com.teamfp.aistock.domain.admin.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.admin.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // 관리자 작업 감사 로그 검색·목록(ADMIN_API_BACKEND_HANDOFF.md 5.2). action/adminId/
    // targetType/targetId/from~to 전부 선택 필터다 — UserRepository.searchUsers() 등과 동일한
    // "null이면 조건 없음" 패턴.
    @Query(value = "select a from AuditLog a where "
            + "(:action is null or a.action = :action) "
            + "and (:adminId is null or a.adminUserId = :adminId) "
            + "and (:targetType is null or a.targetType = :targetType) "
            + "and (:targetId is null or a.targetId = :targetId) "
            + "and (:from is null or a.createdAt >= :from) "
            + "and (:to is null or a.createdAt <= :to)",
            countQuery = "select count(a) from AuditLog a where "
            + "(:action is null or a.action = :action) "
            + "and (:adminId is null or a.adminUserId = :adminId) "
            + "and (:targetType is null or a.targetType = :targetType) "
            + "and (:targetId is null or a.targetId = :targetId) "
            + "and (:from is null or a.createdAt >= :from) "
            + "and (:to is null or a.createdAt <= :to)")
    Page<AuditLog> search(
            @Param("action") String action, @Param("adminId") Long adminId, @Param("targetType") String targetType,
            @Param("targetId") Long targetId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            Pageable pageable);
}
