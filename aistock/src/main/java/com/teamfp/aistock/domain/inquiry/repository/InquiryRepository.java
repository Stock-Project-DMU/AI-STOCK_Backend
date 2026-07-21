package com.teamfp.aistock.domain.inquiry.repository;

import java.util.List;
import java.util.Optional;

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

    // status를 알파벳순으로 정렬하면 PENDING이 ANSWERED보다 앞에 오므로,
    // 관리자 전체 목록 조회 시 미답변 문의가 자연스럽게 먼저 노출된다.
    List<Inquiry> findAllByOrderByStatusAscCreatedAtDesc();

    @Modifying
    @Query("delete from Inquiry i where i.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
