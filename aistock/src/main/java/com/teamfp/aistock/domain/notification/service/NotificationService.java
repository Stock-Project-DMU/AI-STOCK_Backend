package com.teamfp.aistock.domain.notification.service;

import java.util.List;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
     * 알림 발송 내부용 (주문 체결, AI 응답 등 다른 도메인 서비스가 호출). 이 메서드는 대부분
     * 호출 측(OrderService.createMarketOrder(), OrderExecutionService.execute() 등)의
     * @Transactional 안에서 참여 트랜잭션으로 호출되므로, DB 저장은 그 트랜잭션에 그대로 맡겨
     * 함께 롤백되게 두고, STOMP 유니캐스팅만 트랜잭션이 실제로 커밋된 뒤로 미룬다(코드리뷰
     * 반영). 커밋 전에 STOMP를 보내면 두 가지 문제가 생긴다 — ① 클라이언트가 알림을 받자마자
     * 관련 데이터(예: 방금 체결된 주문)를 조회해도 커밋 전이라 아직 안 보일 수 있고, ② 이후
     * 같은 트랜잭션에서 예외가 나 전체가 롤백되더라도 이미 나가버린 STOMP 알림은 취소할 수 없다.
     * 접속 중이 아니면 STOMP 전송은 그냥 소실되며 별도 재전송은 하지 않는다 — 사용자는 다음
     * 접속 시 getMyNotifications()로 저장된 알림을 확인한다.
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

        // NotificationResponse.from()은 notification.getUser()(LAZY) 없이 필드만 옮기므로,
        // 커밋 후 영속성 컨텍스트가 닫힌 뒤에도 안전하게 쓸 수 있도록 지금 미리 만들어둔다.
        NotificationResponse response = NotificationResponse.from(notification);
        registerAfterCommit(() -> messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                NOTIFICATION_QUEUE_DESTINATION,
                response
        ));
    }

    /**
     * 현재 진행 중인 트랜잭션이 실제로 커밋된 뒤에만 STOMP 발송을 실행하도록 등록한다.
     * OrderService.registerAfterCommit()과 동일한 패턴 — 단위 테스트처럼 실제 트랜잭션 매니저
     * 없이 notify()를 직접 호출하는 경우(동기화 비활성)에는 즉시 실행으로 대체한다.
     */
    private void registerAfterCommit(Runnable afterCommitTask) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommitTask.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitTask.run();
            }
        });
    }
}
