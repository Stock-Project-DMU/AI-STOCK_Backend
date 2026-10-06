package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationSendRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationTargetType;
import com.teamfp.aistock.domain.admin.dto.response.AdminNotificationSendResponse;
import com.teamfp.aistock.domain.notification.entity.Notice;
import com.teamfp.aistock.domain.notification.entity.NoticeTargetType;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.repository.NoticeRepository;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private NoticeRepository noticeRepository;

    private AdminNotificationService adminNotificationService;

    private static final Long ADMIN_ID = 100L;
    private static final Long USER_ID = 1L;
    private static final AdminNotificationRequest REQUEST =
            new AdminNotificationRequest("계좌 상태 변경 안내", "계좌 거래 정지가 해제되었습니다.", NotificationType.SYSTEM, null, null);

    @BeforeEach
    void setUp() {
        adminNotificationService = new AdminNotificationService(userRepository, notificationService, noticeRepository);
        // 저장한 공지에 번호를 붙여 그대로 돌려준다(실제 IDENTITY 저장과 같은 동작).
        lenient().when(noticeRepository.save(any(Notice.class))).thenAnswer(invocation -> {
            Notice notice = invocation.getArgument(0);
            ReflectionTestUtils.setField(notice, "noticeId", 7L);
            return notice;
        });
        User admin = User.builder().loginId("admin1").name("관리자").role(Role.ADMIN).isActive(true).build();
        lenient().when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
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

    // 공지는 발송 전에 한 번, 발송 결과를 기록하며 한 번 더 저장된다(같은 객체).
    private Notice savedNotice() {
        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(0);
    }

    @Test
    @DisplayName("notifyUser()는 공지(SINGLE)를 남기고 그 회원에게 공지 알림을 보낸다")
    void notifyUser_savesNoticeAndNotifies() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(activeUser()));

        AdminNotificationSendResponse result = adminNotificationService.notifyUser(ADMIN_ID, USER_ID, REQUEST);

        Notice notice = savedNotice();
        assertThat(notice.getTargetType()).isEqualTo(NoticeTargetType.SINGLE);
        assertThat(notice.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(notice.getCreatedByLoginId()).isEqualTo("admin1");
        assertThat(notice.getSentCount()).isEqualTo(1);
        assertThat(result.noticeId()).isEqualTo(7L);
        verify(notificationService).notifyNotice(USER_ID, notice);
    }

    @Test
    @DisplayName("notifyUser()는 존재하지 않는(또는 탈퇴한) 유저면 USER_NOT_FOUND, 공지도 알림도 남기지 않는다")
    void notifyUser_userNotFound() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminNotificationService.notifyUser(ADMIN_ID, USER_ID, REQUEST))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(noticeRepository, never()).save(any());
        verify(notificationService, never()).notifyNotice(anyLong(), any());
    }

    @Test
    @DisplayName("broadcast()는 공지(ALL)를 하나 남기고 활성 유저 전체에게 각각 보낸다")
    void broadcast_notifiesAllActiveUsers() {
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(1L, 2L, 3L));

        adminNotificationService.broadcast(ADMIN_ID, REQUEST);

        Notice notice = savedNotice();
        assertThat(notice.getTargetType()).isEqualTo(NoticeTargetType.ALL);
        verify(notificationService, times(1)).notifyNotice(1L, notice);
        verify(notificationService, times(1)).notifyNotice(2L, notice);
        verify(notificationService, times(1)).notifyNotice(3L, notice);
    }

    @Test
    @DisplayName("broadcast()는 중간 유저 처리가 실패해도 예외를 흡수하고 나머지 유저는 계속 처리한다(코드리뷰 반영)")
    void broadcast_continuesAfterOneUserFails() {
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(1L, 2L, 3L));
        lenient().doThrow(new RuntimeException("일시적 DB 오류"))
                .when(notificationService).notifyNotice(eq(2L), any());

        // 예외가 broadcast() 밖으로 전파되지 않아야 한다 — 전파되면 @Transactional이 전체를
        // 롤백시켜 이미 처리된 1번 유저의 알림까지 취소된다.
        AdminNotificationSendResponse result = adminNotificationService.broadcast(ADMIN_ID, REQUEST);

        verify(notificationService).notifyNotice(eq(1L), any());
        verify(notificationService).notifyNotice(eq(3L), any());
        // 대상 3명 중 실제로 저장된 2명이 sentCount로 돌아오고 공지에도 기록된다.
        assertThat(result.targetCount()).isEqualTo(3);
        assertThat(result.sentCount()).isEqualTo(2);
        assertThat(savedNotice().getSentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("선택 발송(SELECTED)은 고른 회원 중 탈퇴하지 않은 회원에게만 보내고, 중복 번호는 한 번만 보낸다")
    void send_selected_onlyActiveUsers() {
        User active = activeUser();
        User withdrawn = User.builder().loginId("gone").name("탈퇴").role(Role.USER).isActive(false).build();
        ReflectionTestUtils.setField(withdrawn, "userId", 2L);
        when(userRepository.findAllById(new java.util.LinkedHashSet<>(List.of(USER_ID, 2L, 99L))))
                .thenReturn(List.of(active, withdrawn));

        AdminNotificationSendResponse result = adminNotificationService.send(ADMIN_ID, sendRequest(
                AdminNotificationTargetType.SELECTED, List.of(USER_ID, 2L, 99L, USER_ID), null));

        assertThat(result.targetCount()).isEqualTo(1);
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(savedNotice().getTargetType()).isEqualTo(NoticeTargetType.SELECTED);
        verify(notificationService).notifyNotice(eq(USER_ID), any());
        verify(notificationService, never()).notifyNotice(eq(2L), any());
    }

    @Test
    @DisplayName("선택 발송(SELECTED)에서 아무도 고르지 않으면 INVALID_INPUT, 공지를 남기지 않는다")
    void send_selected_empty() {
        assertThatThrownBy(() -> adminNotificationService.send(ADMIN_ID,
                sendRequest(AdminNotificationTargetType.SELECTED, List.of(), null)))
                .isInstanceOf(CustomException.class)
                .hasMessage(AdminNotificationService.NO_TARGET_MESSAGE);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    @DisplayName("전체 선택(ALL)은 회원 목록과 같은 검색 조건의 결과 전체에서 제외 목록만 빼고 보낸다(공지 SEARCH)")
    void send_all_searchResultMinusExcluded() {
        User first = activeUser();
        User second = User.builder().loginId("tester2").name("테스터2").role(Role.USER).isActive(true).build();
        ReflectionTestUtils.setField(second, "userId", 2L);
        when(userRepository.searchUsers("test", "%test%", null, "LOGIN_ID", false, null, null,
                org.springframework.data.domain.Pageable.unpaged()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(first, second)));

        AdminNotificationSendResponse result = adminNotificationService.send(ADMIN_ID, new AdminNotificationSendRequest(
                AdminNotificationTargetType.ALL, List.of(777L), List.of(2L), "test",
                com.teamfp.aistock.domain.admin.dto.request.AdminUserSearchField.LOGIN_ID, null, null,
                REQUEST.title(), REQUEST.content(), REQUEST.type(), null, null));

        assertThat(result.targetCount()).isEqualTo(1);
        assertThat(savedNotice().getTargetType()).isEqualTo(NoticeTargetType.SEARCH);
        verify(notificationService).notifyNotice(eq(USER_ID), any());
        verify(notificationService, never()).notifyNotice(eq(2L), any());
        // ALL 모드에서는 userIds를 무시한다.
        verify(notificationService, never()).notifyNotice(eq(777L), any());
    }

    @Test
    @DisplayName("팝업으로 보내면 공지에 게시 종료일이 남고, 팝업이 아니면 종료일을 보내도 무시한다")
    void popupEndDateSavedOnlyWhenPopup() {
        LocalDate endDate = LocalDate.now().plusDays(3);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(1L));

        adminNotificationService.broadcast(ADMIN_ID, new AdminNotificationRequest("점검 안내", "내일 점검합니다.",
                NotificationType.SYSTEM, true, endDate));
        adminNotificationService.broadcast(ADMIN_ID, new AdminNotificationRequest("일반", "팝업 아님",
                NotificationType.SYSTEM, false, endDate));

        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository, times(4)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Notice::getPopupEndDate).containsExactly(endDate, endDate, null, null);
    }

    @Test
    @DisplayName("팝업인데 게시 종료일이 없거나 지났으면 INVALID_INPUT, 공지도 알림도 남기지 않는다")
    void popupRequiresFutureEndDate() {
        for (LocalDate endDate : java.util.Arrays.asList(null, LocalDate.now().minusDays(1))) {
            assertThatThrownBy(() -> adminNotificationService.broadcast(ADMIN_ID, new AdminNotificationRequest("점검", "내용",
                    NotificationType.SYSTEM, true, endDate)))
                    .isInstanceOf(CustomException.class)
                    .hasMessage(AdminNotificationService.INVALID_POPUP_END_DATE_MESSAGE);
        }
        verify(noticeRepository, never()).save(any());
        verify(notificationService, never()).notifyNotice(anyLong(), any());
    }

    @Test
    @DisplayName("받을 회원이 0명이면 공지를 남기지 않고 INVALID_INPUT")
    void noRecipients_rejectedWithoutNotice() {
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of());
        User withdrawn = User.builder().loginId("gone").name("탈퇴").role(Role.USER).isActive(false).build();
        ReflectionTestUtils.setField(withdrawn, "userId", 2L);
        when(userRepository.findAllById(new java.util.LinkedHashSet<>(List.of(2L)))).thenReturn(List.of(withdrawn));

        assertThatThrownBy(() -> adminNotificationService.broadcast(ADMIN_ID, REQUEST))
                .isInstanceOf(CustomException.class)
                .hasMessage(AdminNotificationService.NO_RECIPIENT_MESSAGE);
        assertThatThrownBy(() -> adminNotificationService.send(ADMIN_ID,
                sendRequest(AdminNotificationTargetType.SELECTED, List.of(2L), null)))
                .isInstanceOf(CustomException.class)
                .hasMessage(AdminNotificationService.NO_RECIPIENT_MESSAGE);
        verify(noticeRepository, never()).save(any());
    }

    private AdminNotificationSendRequest sendRequest(AdminNotificationTargetType targetType, List<Long> userIds,
            List<Long> excludedUserIds) {
        return new AdminNotificationSendRequest(targetType, userIds, excludedUserIds, null, null, null, null,
                REQUEST.title(), REQUEST.content(), REQUEST.type(), null, null);
    }
}
