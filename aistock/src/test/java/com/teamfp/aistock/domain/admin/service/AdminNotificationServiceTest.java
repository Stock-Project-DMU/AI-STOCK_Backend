package com.teamfp.aistock.domain.admin.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNotificationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    private AdminNotificationService adminNotificationService;

    private static final Long USER_ID = 1L;
    private static final AdminNotificationRequest REQUEST =
            new AdminNotificationRequest("계좌 상태 변경 안내", "계좌 거래 정지가 해제되었습니다.", NotificationType.SYSTEM);

    @BeforeEach
    void setUp() {
        adminNotificationService = new AdminNotificationService(userRepository, notificationService);
    }

    private User activeUser() {
        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        return user;
    }

    @Test
    @DisplayName("notifyUser()는 존재하는 유저면 NotificationService.notify()를 그대로 위임한다")
    void notifyUser_delegatesToNotificationService() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(activeUser()));

        adminNotificationService.notifyUser(USER_ID, REQUEST);

        verify(notificationService).notify(USER_ID, REQUEST.type(), REQUEST.title(), REQUEST.content());
    }

    @Test
    @DisplayName("notifyUser()는 존재하지 않는(또는 탈퇴한) 유저면 USER_NOT_FOUND 예외를 던지고 notify()를 호출하지 않는다")
    void notifyUser_userNotFound() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminNotificationService.notifyUser(USER_ID, REQUEST))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(notificationService, never()).notify(anyLong(), any(NotificationType.class), anyString(), anyString());
    }

    @Test
    @DisplayName("broadcast()는 활성 유저 전체에게 각각 notify()를 호출한다")
    void broadcast_notifiesAllActiveUsers() {
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(1L, 2L, 3L));

        adminNotificationService.broadcast(REQUEST);

        verify(notificationService, times(1)).notify(1L, REQUEST.type(), REQUEST.title(), REQUEST.content());
        verify(notificationService, times(1)).notify(2L, REQUEST.type(), REQUEST.title(), REQUEST.content());
        verify(notificationService, times(1)).notify(3L, REQUEST.type(), REQUEST.title(), REQUEST.content());
    }

    @Test
    @DisplayName("broadcast()는 중간 유저 처리가 실패해도 예외를 흡수하고 나머지 유저는 계속 처리한다(코드리뷰 반영)")
    void broadcast_continuesAfterOneUserFails() {
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(1L, 2L, 3L));
        org.mockito.Mockito.doThrow(new RuntimeException("일시적 DB 오류"))
                .when(notificationService).notify(2L, REQUEST.type(), REQUEST.title(), REQUEST.content());

        // 예외가 broadcast() 밖으로 전파되지 않아야 한다 — 전파되면 @Transactional이 전체를
        // 롤백시켜 이미 처리된 1번 유저의 알림까지 취소된다.
        adminNotificationService.broadcast(REQUEST);

        verify(notificationService).notify(1L, REQUEST.type(), REQUEST.title(), REQUEST.content());
        verify(notificationService).notify(2L, REQUEST.type(), REQUEST.title(), REQUEST.content());
        verify(notificationService).notify(3L, REQUEST.type(), REQUEST.title(), REQUEST.content());
    }
}
