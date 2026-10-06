package com.teamfp.aistock.domain.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    // 거래 상세의 취소 정보(feat/admin-improvements) — 같은 대상에 대한 같은 작업 중 가장 최근 기록 1건.
    Optional<AuditLog> findFirstByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(String action, String targetType, Long targetId);

    // 계좌·회원 상세의 정지 이력(feat/admin-improvements) — 같은 대상에 대한 같은 작업 기록 전체(최신순).
    List<AuditLog> findAllByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(String action, String targetType, Long targetId);

    // 관리자 회원 상세의 정지 이력(feat/admin-improvements) — 회원 정지·해제 + 그 회원 계좌의 거래 정지·해제(최신순).
    // accountIds가 비면 IN ()이 SQL 오류라 호출 쪽에서 존재하지 않는 ID(-1) 하나를 넣는다.
    @Query("select a from AuditLog a where "
            + "(a.action = 'USER_STATUS_CHANGE' and a.targetType = 'USER' and a.targetId = :userId) "
            + "or (a.action = 'ACCOUNT_STATUS_CHANGE' and a.targetType = 'ACCOUNT' and a.targetId in :accountIds) "
            + "order by a.createdAt desc, a.auditLogId desc")
    List<AuditLog> findSuspensionHistory(@Param("userId") Long userId, @Param("accountIds") List<Long> accountIds);

    // 관리자 회원 상세의 관리자 처리 이력(feat/admin-improvements) — 이 회원·회원의 계좌·그 계좌의 주문·충전 요청을
    // 대상으로 한 모든 관리자 작업(정지, 잔고 조정, 주문 강제 취소, 충전 승인·거절 등)을 최신순으로.
    @Query(value = "select a from AuditLog a where "
            + "(a.targetType = 'USER' and a.targetId = :userId) "
            + "or (a.targetType = 'ACCOUNT' and a.targetId in :accountIds) "
            + "or (a.targetType = 'ORDER' and a.targetId in "
            + "(select o.orderId from Order o where o.account.accountId in :accountIds)) "
            + "or (a.targetType = 'CHARGE_REQUEST' and a.targetId in "
            + "(select c.requestId from ChargeRequest c where c.account.accountId in :accountIds)) "
            + "order by a.createdAt desc, a.auditLogId desc",
            countQuery = "select count(a) from AuditLog a where "
            + "(a.targetType = 'USER' and a.targetId = :userId) "
            + "or (a.targetType = 'ACCOUNT' and a.targetId in :accountIds) "
            + "or (a.targetType = 'ORDER' and a.targetId in "
            + "(select o.orderId from Order o where o.account.accountId in :accountIds)) "
            + "or (a.targetType = 'CHARGE_REQUEST' and a.targetId in "
            + "(select c.requestId from ChargeRequest c where c.account.accountId in :accountIds))")
    Page<AuditLog> findActionsForUser(@Param("userId") Long userId, @Param("accountIds") List<Long> accountIds,
            Pageable pageable);

    // 관리자 전체 활동 기록의 거래 한 줄에 관리자 강제 취소 정보를 붙이는 데 쓴다(feat/admin-improvements).
    List<AuditLog> findAllByActionAndTargetTypeAndTargetIdIn(String action, String targetType, List<Long> targetIds);
}
