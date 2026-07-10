package com.teamfp.aistock.domain.ai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.AiPlanningMessage;

public interface AiPlanningMessageRepository extends JpaRepository<AiPlanningMessage, Long> {

    @Query("select m from AiPlanningMessage m where m.session.sessionId = :sessionId order by m.createdAt asc")
    List<AiPlanningMessage> findAllBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);
}
