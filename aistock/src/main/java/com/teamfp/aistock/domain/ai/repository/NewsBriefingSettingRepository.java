package com.teamfp.aistock.domain.ai.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;

public interface NewsBriefingSettingRepository extends JpaRepository<NewsBriefingSetting, Long> {

    // User.userId는 필드명이 "id"가 아니라 "userId"라서 파생 쿼리(findByUserId)로는
    // "user.id"를 찾다가 PropertyReferenceException이 난다(InquiryRepository와 동일한 이유로
    // @Query 명시 필요).
    @Query("select s from NewsBriefingSetting s where s.user.userId = :userId")
    Optional<NewsBriefingSetting> findByUserId(@Param("userId") Long userId);

    // 스케줄러가 매분 실행될 때 사용 — 예전엔 findAllWithUser()로 전체 사용자를 다 불러온
    // 다음 메모리에서 시각을 비교했는데(PR#45 리뷰 반려 사유 4번, 2026-09-28), DB 조건
    // 필터링으로 바꾸면서도 처음엔 매초 실행 + briefingTime 정확히 일치(=)로만 고쳤었다.
    // 우혁이 재반려하며 그걸로는 부족하다고 지적함(2026-09-29) — 정확히 일치하는 비교라
    // 앞 사용자 처리가 1초를 넘기면 그 초가 지나가버려서 뒤에 밀린 사용자는 그날 브리핑을
    // 통째로 놓칠 위험이 여전했다. 지금은 "설정 시각이 지금 이 순간 이전(<=)이고 아직
    // 오늘자 브리핑 생성 시도를 아직 하지 않은" 사용자를 찾는다 — 이번 분에 못 챙겨도
    // 다음 분 폴링에서 다시 잡힌다. 생성 시도를 시작한 사용자는 lastAttemptDate로 제외한다.
    // 초 단위 정밀도는 매분 폴링이므로 보장하지 않는다. User를 함께 fetch join해
    // (스케줄 작업은 @PostConstruct와 마찬가지로 트랜잭션 밖에서 결과를 순회하는 경우가
    // 많아) LAZY 프록시로 인한 LazyInitializationException을 피한다.
    @Query("SELECT s FROM NewsBriefingSetting s JOIN FETCH s.user u "
            + "WHERE s.briefingTime <= :now "
            + "AND (s.lastAttemptDate IS NULL OR s.lastAttemptDate < :today) "
            + "AND NOT EXISTS (SELECT 1 FROM NewsBriefing n WHERE n.user.userId = u.userId AND n.briefingDate = :today)")
    List<NewsBriefingSetting> findDueSettings(@Param("now") LocalTime now,
                                               @Param("today") LocalDate today);

    @Transactional
    @Modifying
    @Query("update NewsBriefingSetting s set s.lastAttemptDate = :date, s.lastAttemptAt = :attemptedAt where s.settingId = :settingId and (s.lastAttemptDate is null or s.lastAttemptDate < :date)")
    int claimBriefingAttempt(@Param("settingId") Long settingId, @Param("date") LocalDate date, @Param("attemptedAt") LocalDateTime attemptedAt);

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    @Modifying
    @Query("delete from NewsBriefingSetting s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
