package com.teamfp.aistock.domain.notification.service;

import java.util.List;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.notification.dto.response.NotificationCountResponse;
import com.teamfp.aistock.domain.notification.dto.response.NotificationResponse;
import com.teamfp.aistock.domain.notification.entity.Notification;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.repository.NotificationRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    // CLAUDE.md 8번 항목의 STOMP 유니캐스팅 규칙("/user/{userId}/queue")을 그대로 따른다.
    // convertAndSendToUser(userId, "/queue", payload)로 보내면 클라이언트는 "/user/queue"를
    // 구독해 자신에게 온 알림만 받는다.
    private static final String NOTIFICATION_QUEUE_DESTINATION = "/queue";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 내 알림 목록 조회 (최신순)
     */
    public List<NotificationResponse> getMyNotifications(Long userId) {
        return notificationRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    /**
     * 안 읽은 알림 개수 조회
     */
    public NotificationCountResponse getUnreadCount(Long userId) {
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return NotificationCountResponse.from(unreadCount);
    }

    /**
     * 알림 읽음 처리. notiId+userId로 함께 조회해 본인 알림이 아니면 NOTIFICATION_NOT_FOUND로 응답한다.
     */
    @Transactional
    public void markAsRead(Long userId, Long notiId) {
        Notification notification = notificationRepository.findByNotiIdAndUserId(notiId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markAsRead();
    }

    /**
     * 알림 발송 내부용 (주문 체결, AI 응답 등 다른 도메인 서비스가 호출). DB에 저장함과 동시에
     * 현재 접속 중인 클라이언트에게 STOMP로 즉시 유니캐스팅한다. 접속 중이 아니면 STOMP 전송은
     * 그냥 소실되며 별도 재전송은 하지 않는다 — 사용자는 다음 접속 시 getMyNotifications()로
     * 저장된 알림을 확인한다.
     */
    @Transactional
    public void notify(Long userId, NotificationType type, String title, String content) {
        User user = userRepository.getReferenceById(userId);
        Notification notification = Notification.builder()
                .user(user)
                .type(type)
                .title(title)
                .content(content)
                .build();
        notificationRepository.save(notification);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                NOTIFICATION_QUEUE_DESTINATION,
                NotificationResponse.from(notification)
        );
    }
}
