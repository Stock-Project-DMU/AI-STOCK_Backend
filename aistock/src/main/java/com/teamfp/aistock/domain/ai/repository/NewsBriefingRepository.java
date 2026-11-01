package com.teamfp.aistock.domain.ai.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.NewsBriefing;

public interface NewsBriefingRepository extends JpaRepository<NewsBriefing, Long> {
    java.util.List<NewsBriefing> findTop100ByUserUserIdOrderByBriefingDateDesc(Long userId);

    @Query("select distinct n.outletDomain, n.briefingTime from NewsBriefing n where n.user.userId = :userId")
    List<Object[]> findDistinctSettingsByUserId(@Param("userId") Long userId);

    // User.userId는 필드명이 "id"가 아니라 "userId"라서 파생 쿼리(findByUserId...)로는
    // "user.id"를 찾다가 PropertyReferenceException이 난다(InquiryRepository와 동일한 이유로
    // @Query 명시 필요).
    // 브리핑은 설정(언론사+시각)마다 하루 1건이라(fix/site-polish, 017_site_polish.sql) 같은 날짜에 여러 건일 수 있다 —
    // 날짜만으로 찾는 곳은 가장 나중에 만든 것부터 받는다.
    @Query("select n from NewsBriefing n where n.user.userId = :userId and n.briefingDate = :briefingDate order by n.createdAt desc, n.briefingId desc")
    List<NewsBriefing> findAllByUserIdAndBriefingDate(@Param("userId") Long userId, @Param("briefingDate") LocalDate briefingDate);

    // 설정 하나(언론사+시각)의 그날 브리핑. briefingTime이 null인 행은 시각 저장 전에 만든 예전 브리핑이다.
    @Query("select n from NewsBriefing n where n.user.userId = :userId and n.briefingDate = :briefingDate "
            + "and n.outletDomain = :outletDomain "
            + "and ((:briefingTime is null and n.briefingTime is null) or n.briefingTime = :briefingTime)")
    Optional<NewsBriefing> findByUserIdAndBriefingDateAndSetting(@Param("userId") Long userId,
                                                                 @Param("briefingDate") LocalDate briefingDate,
                                                                 @Param("outletDomain") String outletDomain,
                                                                 @Param("briefingTime") LocalTime briefingTime);

    @Query("select case when count(n) > 0 then true else false end from NewsBriefing n where n.user.userId = :userId and n.briefingDate = :briefingDate")
    boolean existsByUserIdAndBriefingDate(@Param("userId") Long userId, @Param("briefingDate") LocalDate briefingDate);

    @Query("select n from NewsBriefing n where n.user.userId = :userId and n.briefingDate in :dates order by n.briefingDate desc, n.createdAt desc, n.briefingId desc")
    List<NewsBriefing> findByUserIdAndBriefingDateIn(@Param("userId") Long userId, @Param("dates") List<LocalDate> dates);

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    @Modifying
    @Query("delete from NewsBriefing n where n.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
