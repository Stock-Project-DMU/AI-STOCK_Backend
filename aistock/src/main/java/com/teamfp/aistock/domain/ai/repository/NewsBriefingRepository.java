package com.teamfp.aistock.domain.ai.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.teamfp.aistock.domain.ai.entity.NewsBriefing;

public interface NewsBriefingRepository extends JpaRepository<NewsBriefing, Long> {

    Optional<NewsBriefing> findByUserIdAndBriefingDate(Long userId, LocalDate briefingDate);

    boolean existsByUserIdAndBriefingDate(Long userId, LocalDate briefingDate);

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    void deleteByUserId(Long userId);
}
