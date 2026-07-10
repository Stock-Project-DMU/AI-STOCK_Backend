package com.teamfp.aistock.domain.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.AiPlanningSession;

public interface AiPlanningSessionRepository extends JpaRepository<AiPlanningSession, Long> {

    @Query("select s from AiPlanningSession s where s.user.userId = :userId order by s.updatedAt desc")
    List<AiPlanningSession> findAllByUserIdOrderByUpdatedAtDesc(@Param("userId") Long userId);

    @Query("select s from AiPlanningSession s where s.user.userId = :userId and s.sessionId = :sessionId")
    Optional<AiPlanningSession> findByUserIdAndSessionId(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Modifying
    @Query("delete from AiPlanningSession s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
