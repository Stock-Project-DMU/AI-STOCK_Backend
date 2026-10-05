package com.teamfp.aistock.domain.inquiry.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    // 관리자 "전체 활동 기록"(AdminActivityService, feat/admin-improvements) — 한 페이지에 나온 문의 등록·답변을
    // 작성자·답변 관리자까지 한 번에 조회
    @Query("select i from Inquiry i join fetch i.user left join fetch i.answeredBy where i.inquiryId in :inquiryIds")
    List<Inquiry> findAllWithUserByInquiryIdIn(@Param("inquiryIds") List<Long> inquiryIds);

    @Query("select i from Inquiry i where i.user.userId = :userId order by i.createdAt desc")
    List<Inquiry> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("select i from Inquiry i where i.inquiryId = :inquiryId and i.user.userId = :userId")
    Optional<Inquiry> findByInquiryIdAndUserId(@Param("inquiryId") Long inquiryId, @Param("userId") Long userId);

    // "PENDING"이 "ANSWERED"보다 알파벳순으로 뒤(P > A)이므로, status를 내림차순(Desc)으로
    // 정렬해야 미답변(PENDING) 문의가 관리자 전체 목록에서 먼저 노출된다.
    List<Inquiry> findAllByOrderByStatusDescCreatedAtDesc();

    // 관리자 문의 목록 검색(feat/admin-improvements) — 다른 관리자 목록(ChargeRequestRepository.searchWithAccountAndUser)과
    // 같은 검색 규칙. 파라미터는 AdminSearchConditionDto가 만든다 — INQUIRY_ID는 queryId와 정확히 일치할 때만, 문자열
    // 항목은 exact면 =, 아니면 LIKE(:pattern, '!' 이스케이프). status는 선택 필터, 정렬은 Pageable(AdminSortSupport.inquiries).
    // 작성자·답변 관리자까지 fetch join해 AdminInquiryResponse.from()의 N+1을 막는다.
    @Query(value = "select i from Inquiry i join fetch i.user u left join fetch i.answeredBy where (:query is null "
            + "or (((:field = 'ALL' or :field = 'INQUIRY_ID') and i.inquiryId = :queryId) or ((:field = 'ALL' "
            + "or :field = 'LOGIN_ID') and ((:exact = true and u.loginId = :query) or (:exact = false and "
            + "u.loginId like :pattern escape '!'))) or ((:field = 'ALL' or :field = 'NAME') and ((:exact = "
            + "true and u.name = :query) or (:exact = false and u.name like :pattern escape '!'))) or ((:field "
            + "= 'ALL' or :field = 'TITLE') and ((:exact = true and i.title = :query) or (:exact = false and "
            + "i.title like :pattern escape '!'))))) and (:status is null or i.status = :status)",
            countQuery = "select count(i) from Inquiry i join i.user u where (:query is null or (((:field = 'ALL' or "
            + ":field = 'INQUIRY_ID') and i.inquiryId = :queryId) or ((:field = 'ALL' or :field = 'LOGIN_ID') "
            + "and ((:exact = true and u.loginId = :query) or (:exact = false and u.loginId like :pattern "
            + "escape '!'))) or ((:field = 'ALL' or :field = 'NAME') and ((:exact = true and u.name = :query) "
            + "or (:exact = false and u.name like :pattern escape '!'))) or ((:field = 'ALL' or :field = "
            + "'TITLE') and ((:exact = true and i.title = :query) or (:exact = false and i.title like :pattern "
            + "escape '!'))))) and (:status is null or i.status = :status)")
    Page<Inquiry> searchWithUser(@Param("query") String query, @Param("pattern") String pattern,
            @Param("queryId") Long queryId, @Param("field") String field, @Param("exact") boolean exact,
            @Param("status") InquiryStatus status, Pageable pageable);

    @Modifying
    @Query("delete from Inquiry i where i.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
