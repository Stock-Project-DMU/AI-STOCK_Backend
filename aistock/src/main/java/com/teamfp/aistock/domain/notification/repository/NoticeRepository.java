package com.teamfp.aistock.domain.notification.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.notification.entity.Notice;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    // 관리자 알림 관리 목록 검색(feat/admin-improvements) — 다른 관리자 목록과 같은 검색 규칙(AdminSearchConditionDto).
    // NOTICE_ID는 queryId와 정확히 일치, TITLE·ADMIN_LOGIN_ID(보낸 관리자 아이디)는 exact면 =, 아니면 LIKE.
    // popup: true면 팝업 공지만, false면 일반 공지만, null이면 전체. 정렬은 Pageable(AdminSortSupport.notices).
    @Query("select n from Notice n where (:query is null or (((:field = 'ALL' or :field = 'NOTICE_ID') "
            + "and n.noticeId = :queryId) or ((:field = 'ALL' or :field = 'TITLE') and ((:exact = true and n.title = :query) "
            + "or (:exact = false and n.title like :pattern escape '!'))) or ((:field = 'ALL' or :field = 'ADMIN_LOGIN_ID') "
            + "and ((:exact = true and n.createdByLoginId = :query) "
            + "or (:exact = false and n.createdByLoginId like :pattern escape '!'))))) "
            + "and (:popup is null or (:popup = true and n.popupEndDate is not null) "
            + "or (:popup = false and n.popupEndDate is null))")
    Page<Notice> search(@Param("query") String query, @Param("pattern") String pattern, @Param("queryId") Long queryId,
            @Param("field") String field, @Param("exact") boolean exact, @Param("popup") Boolean popup, Pageable pageable);
}
