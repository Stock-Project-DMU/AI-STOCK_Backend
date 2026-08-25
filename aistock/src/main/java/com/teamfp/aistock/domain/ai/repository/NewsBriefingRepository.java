package com.teamfp.aistock.domain.ai.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.NewsBriefing;

public interface NewsBriefingRepository extends JpaRepository<NewsBriefing, Long> {

    // User.userId는 필드명이 "id"가 아니라 "userId"라서 파생 쿼리(findByUserId...)로는
    // "user.id"를 찾다가 PropertyReferenceException이 난다(InquiryRepository와 동일한 이유로
    // @Query 명시 필요).
    @Query("select n from NewsBriefing n where n.user.userId = :userId and n.briefingDate = :briefingDate")
    Optional<NewsBriefing> findByUserIdAndBriefingDate(@Param("userId") Long userId, @Param("briefingDate") LocalDate briefingDate);

    @Query("select case when count(n) > 0 then true else false end from NewsBriefing n where n.user.userId = :userId and n.briefingDate = :briefingDate")
    boolean existsByUserIdAndBriefingDate(@Param("userId") Long userId, @Param("briefingDate") LocalDate briefingDate);

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    @Modifying
    @Query("delete from NewsBriefing n where n.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
