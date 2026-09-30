package com.teamfp.aistock.domain.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.NewsChatSession;

public interface NewsChatSessionRepository extends JpaRepository<NewsChatSession, Long> {
    @Query("select s from NewsChatSession s where s.user.userId = :userId and s.settingKey = :settingKey")
    Optional<NewsChatSession> findByUserAndSettingKey(@Param("userId") Long userId, @Param("settingKey") String settingKey);

    @Query("select s from NewsChatSession s where s.user.userId = :userId and s.sessionId = :sessionId")
    Optional<NewsChatSession> findByUserAndSessionId(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Query("select s from NewsChatSession s where s.user.userId = :userId order by s.updatedAt desc, s.sessionId desc")
    List<NewsChatSession> findAllByUser(@Param("userId") Long userId);
}
