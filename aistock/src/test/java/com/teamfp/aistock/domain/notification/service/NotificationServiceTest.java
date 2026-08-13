package com.teamfp.aistock.domain.notification.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.repository.NotificationRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PR 코드리뷰 반영 — notify()가 호출한 쪽(OrderService.createMarketOrder() 등)의 트랜잭션
 * 커밋 전에 STOMP를 먼저 보내버리던 버그를 막기 위해, 발송을
 * TransactionSynchronizationManager 기반 afterCommit 콜백으로 미루도록 고쳤다. 이 테스트는
 * 그 타이밍만 집중 검증한다 — 실제 DB/트랜잭션 매니저 없이도 TransactionSynchronizationManager를
 * 직접 활성화/트리거해서 커밋 전/후를 흉내낼 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private NotificationService notificationService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, userRepository, messagingTemplate);
    }

    @AfterEach
    void tearDown() {
        // 테스트가 initSynchronization()만 하고 끝나면 이후 다른 테스트(같은 스레드)에 상태가
        // 새어나갈 수 있어 매번 정리한다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 동기화가 활성화돼 있으면 DB 저장은 즉시 되지만 STOMP는 커밋 전까지 보내지 않는다")
    void notify_savesImmediately_butDoesNotSendBeforeCommit() {
        TransactionSynchronizationManager.initSynchronization();

        notificationService.notify(USER_ID, NotificationType.ORDER, "주문 체결", "삼성전자 매수 10주가 70,000원에 체결되었습니다.");

        verify(notificationRepository).save(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("트랜잭션이 실제로 커밋된 뒤에야 STOMP를 유니캐스팅한다")
    void notify_sendsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        notificationService.notify(USER_ID, NotificationType.ORDER, "주문 체결", "삼성전자 매수 10주가 70,000원에 체결되었습니다.");
        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq(String.valueOf(USER_ID)), eq("/queue"), any());
    }

    @Test
    @DisplayName("트랜잭션 동기화가 비활성 상태(단위 테스트 등)면 즉시 발송한다")
    void notify_sendsImmediately_whenNoActiveTransactionSynchronization() {
        notificationService.notify(USER_ID, NotificationType.ORDER, "주문 체결", "삼성전자 매수 10주가 70,000원에 체결되었습니다.");

        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq(String.valueOf(USER_ID)), eq("/queue"), any());
    }
}
