package com.teamfp.aistock.domain.ai.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;

public interface NewsBriefingSettingRepository extends JpaRepository<NewsBriefingSetting, Long> {

    // User.userId는 필드명이 "id"가 아니라 "userId"라서 파생 쿼리(findByUserId)로는
    // "user.id"를 찾다가 PropertyReferenceException이 난다(InquiryRepository와 동일한 이유로
    // @Query 명시 필요).
    @Query("select s from NewsBriefingSetting s where s.user.userId = :userId")
    Optional<NewsBriefingSetting> findByUserId(@Param("userId") Long userId);

    // 스케줄러가 매초 실행될 때 사용 — 예전엔 findAllWithUser()로 전체 사용자를 다 불러온
    // 다음 메모리에서 시각을 비교했는데(PR#45 리뷰 반려 사유 4번, 2026-09-28), 그 방식은
    // 사용자가 많아질수록 매초 테이블 전체를 훑는 부담이 커지고, 한 사용자 처리가 오래
    // 걸려 다음 초로 넘어가면 뒤에 밀린 사용자는 그날 브리핑을 통째로 놓칠 위험이 있었다.
    // 이제는 "지금 이 시각에 해당하고 아직 오늘자 브리핑을 못 받은" 사용자만 DB에서 바로
    // 걸러오므로 초 단위 정밀도(v15)는 그대로 유지하면서 매초 전체조회 부담만 없앤다.
    // briefingTime이 TIME 컬럼(LocalTime)이라 파라미터로 받은 시각과 등호(=) 비교만 하면
    // 되므로, HOUR()/MINUTE()/SECOND() 함수로 쪼개 비교할 필요가 없다 — 함수로 감싸면
    // 나중에 briefing_time에 인덱스를 추가해도 못 타므로(비-sargable) 일부러 단순 등호로
    // 남긴다. 처리가 밀려 이번 초를 놓쳐도 "오늘자 브리핑 없음" 조건이 없으므로(이 쿼리는
    // 정확히 그 초에만 매칭) 그날은 놓친 채로 넘어간다 — 자연 재시도까지 원하면 exact
    // match 대신 "briefingTime <= now()" 범위 매칭으로 바꿔야 하는데, 그러면 초 단위로
    // 정확히 그 시각에만 돌던 기존 동작(사용자가 실제 확인한 22:19:15 케이스)이 바뀌므로
    // 이번 수정 범위에서는 건드리지 않는다. User를 함께 fetch join해(스케줄 작업은
    // @PostConstruct와 마찬가지로 트랜잭션 밖에서 결과를 순회하는 경우가 많아) LAZY 프록시로
    // 인한 LazyInitializationException을 피한다.
    @Query("SELECT s FROM NewsBriefingSetting s JOIN FETCH s.user u "
            + "WHERE s.briefingTime = :briefingTime "
            + "AND NOT EXISTS (SELECT 1 FROM NewsBriefing n WHERE n.user.userId = u.userId AND n.briefingDate = :today)")
    List<NewsBriefingSetting> findDueSettings(@Param("briefingTime") LocalTime briefingTime,
                                               @Param("today") LocalDate today);

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    @Modifying
    @Query("delete from NewsBriefingSetting s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
