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

    // "PENDING"이 "ANSWERED"보다 알파벳순으로 뒤(P > A)이므로, status를 내림차순(Desc)으로
    // 정렬해야 미답변(PENDING) 문의가 관리자 전체 목록에서 먼저 노출된다.
    List<Inquiry> findAllByOrderByStatusDescCreatedAtDesc();

    @Modifying
    @Query("delete from Inquiry i where i.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
