package com.teamfp.aistock.domain.ai.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.NewsChatMessage;

public interface NewsChatMessageRepository extends JpaRepository<NewsChatMessage, Long> {
    @Query("select m from NewsChatMessage m where m.session.sessionId = :sessionId order by m.messageId asc")
    List<NewsChatMessage> findAllBySession(@Param("sessionId") Long sessionId);

    @Query("select m from NewsChatMessage m where m.session.sessionId = :sessionId order by m.messageId desc")
    List<NewsChatMessage> findRecentBySession(@Param("sessionId") Long sessionId, Pageable pageable);

    @Modifying
    @Query("delete from NewsChatMessage m where m.session.sessionId = :sessionId")
    void deleteAllBySession(@Param("sessionId") Long sessionId);
}
