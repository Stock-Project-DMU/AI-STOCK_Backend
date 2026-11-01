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

    // 삭제한 채팅은 열거나 목록에 보이지 않는다(fix/site-polish). findByUserAndSettingKey는 sync()가 삭제 여부를 보고
    // 되살릴지 정해야 하므로 걸러내지 않는다.
    @Query("select s from NewsChatSession s where s.user.userId = :userId and s.sessionId = :sessionId and s.deletedAt is null")
    Optional<NewsChatSession> findByUserAndSessionId(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Query("select s from NewsChatSession s where s.user.userId = :userId and s.deletedAt is null order by s.updatedAt desc, s.sessionId desc")
    List<NewsChatSession> findAllByUser(@Param("userId") Long userId);
}
