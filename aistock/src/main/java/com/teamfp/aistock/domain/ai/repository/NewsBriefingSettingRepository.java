package com.teamfp.aistock.domain.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;

public interface NewsBriefingSettingRepository extends JpaRepository<NewsBriefingSetting, Long> {

    Optional<NewsBriefingSetting> findByUserId(Long userId);

    // 스케줄러가 매일 전체 사용자를 순회하며 브리핑을 생성할 때 사용 — User를 함께 fetch join해
    // (스케줄 작업은 @PostConstruct와 마찬가지로 트랜잭션 밖에서 결과를 순회하는 경우가 많아)
    // LAZY 프록시로 인한 LazyInitializationException을 피한다.
    @Query("SELECT s FROM NewsBriefingSetting s JOIN FETCH s.user")
    List<NewsBriefingSetting> findAllWithUser();

    // 탈퇴 처리용 — schema.sql users 테이블 주석의 자식 테이블 명시적 삭제 순서(v12) 참고.
    void deleteByUserId(Long userId);
}
