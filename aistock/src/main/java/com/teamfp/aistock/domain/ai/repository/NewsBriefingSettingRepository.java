package com.teamfp.aistock.domain.ai.repository;

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

    // 스케줄러가 매일 전체 사용자를 순회하며 브리핑을 생성할 때 사용 — User를 함께 fetch join해
    // (스케줄 작업은 @PostConstruct와 마찬가지로 트랜잭션 밖에서 결과를 순회하는 경우가 많아)
    // LAZY 프록시로 인한 LazyInitializationException을 피한다.
    @Query("SELECT s FROM NewsBriefingSetting s JOIN FETCH s.user")
    List<NewsBriefingSetting> findAllWithUser();

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    @Modifying
    @Query("delete from NewsBriefingSetting s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
