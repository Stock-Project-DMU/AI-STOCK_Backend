package com.teamfp.aistock.domain.ai.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.AiPlanningMessage;

public interface AiPlanningMessageRepository extends JpaRepository<AiPlanningMessage, Long> {

    // created_at은 초 단위 정밀도(DATETIME)라 한 턴의 USER/AI 메시지가 같은 초에 저장되면
    // 값이 같아질 수 있다 — messageId(AUTO_INCREMENT, 저장 순서와 항상 일치)를 2차 정렬키로
    // 둬서 동시각 메시지도 실제 저장 순서대로 정렬되게 한다.
    @Query("select m from AiPlanningMessage m where m.session.sessionId = :sessionId order by m.createdAt asc, m.messageId asc")
    List<AiPlanningMessage> findAllBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);

    // Gemini에 보낼 history는 최근 N개만 필요한데, 예전에는 세션 전체 메시지를 findAll로 읽어와
    // Java에서 잘랐다 — 대화가 길어질수록 매 턴마다 불필요한 전체 조회가 반복되는 문제가 있었다.
    // 최신순으로 Pageable(limit)까지만 DB에서 가져오도록 바꾼다(호출부에서 다시 오름차순으로
    // 뒤집어 쓴다). messageId를 2차 정렬키로 두는 이유는 위 asc 메서드와 동일.
    @Query("select m from AiPlanningMessage m where m.session.sessionId = :sessionId order by m.createdAt desc, m.messageId desc")
    List<AiPlanningMessage> findRecentBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);
}
