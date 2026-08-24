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

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    @Query("select i from Inquiry i where i.user.userId = :userId order by i.createdAt desc")
    List<Inquiry> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("select i from Inquiry i where i.inquiryId = :inquiryId and i.user.userId = :userId")
    Optional<Inquiry> findByInquiryIdAndUserId(@Param("inquiryId") Long inquiryId, @Param("userId") Long userId);

    // "PENDING"이 "ANSWERED"보다 알파벳순으로 뒤(P > A)이므로, status를 내림차순(Desc)으로
    // 정렬해야 미답변(PENDING) 문의가 관리자 전체 목록에서 먼저 노출된다.
    List<Inquiry> findAllByOrderByStatusDescCreatedAtDesc();

    // 위 무인자 버전과 같은 정렬 기준의 Pageable 오버로드. feature/admin-inquiry의
    // 관리자 전체 목록(GET /api/admin/inquiries)이 페이징 조회로 이 메서드를 쓴다.
    // AdminTradeService.findAllOrdersWithUser와 동일한 이유로 user까지 fetch join한다 —
    // 파생 쿼리 그대로 두면 AdminInquiryResponse.from()이 매 건마다 inquiry.getUser()를
    // LAZY 로딩하며 N+1이 발생한다(코드리뷰 반영). @Query를 쓰면 메서드 이름의 OrderBy는 더
    // 이상 자동 파싱되지 않으므로(Pageable에 정렬 조건이 없으면 무정렬 조회가 된다),
    // 원래 정렬 기준을 JPQL의 order by로 그대로 옮겨 적는다.
    @Query(value = "select i from Inquiry i join fetch i.user order by i.status desc, i.createdAt desc",
            countQuery = "select count(i) from Inquiry i")
    Page<Inquiry> findAllByOrderByStatusDescCreatedAtDesc(Pageable pageable);

    @Modifying
    @Query("delete from Inquiry i where i.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
