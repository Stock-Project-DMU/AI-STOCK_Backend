package com.teamfp.aistock.domain.notification.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("select n from Notification n where n.user.userId = :userId order by n.createdAt desc")
    List<Notification> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("select count(n) from Notification n where n.user.userId = :userId and n.isRead = false")
    long countByUserIdAndIsReadFalse(@Param("userId") Long userId);

    @Query("select n from Notification n where n.notiId = :notiId and n.user.userId = :userId")
    Optional<Notification> findByNotiIdAndUserId(@Param("notiId") Long notiId, @Param("userId") Long userId);
}
