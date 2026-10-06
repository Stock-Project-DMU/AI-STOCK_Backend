package com.teamfp.aistock.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.response.AdminNoticeResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminNotificationSendResponse;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.repository.NoticeRepository;
import com.teamfp.aistock.domain.notification.repository.NotificationRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;

import jakarta.persistence.EntityManager;

/**
 * 관리자 알림 관리(feat/admin-improvements) — 회원 한 명에게 팝업 공지를 보낸 뒤 목록 검색·상세 건수·팝업 기한 변경/종료·
 * 삭제(받은 회원 알림 함께 삭제)를 실제 DB로 확인한다. @Transactional로 넣은 행은 롤백된다.
 */
@SpringBootTest
@Transactional
class AdminNoticeServiceIntegrationTest {

    @Autowired
    private AdminNotificationService adminNotificationService;
    @Autowired
    private AdminNoticeService adminNoticeService;
    @Autowired
    private NoticeRepository noticeRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("보낸 공지가 알림 관리에 남고, 기한 변경·바로 종료·삭제가 반영된다")
    void noticeLifecycle() {
        LocalDate today = LocalDate.now();
        User admin = userRepository.save(User.builder().loginId("ntadm" + System.nanoTime()).name("공지관리자")
                .role(Role.ADMIN).isActive(true).build());
        User member = userRepository.save(User.builder().loginId("ntmem" + System.nanoTime()).name("공지회원")
                .role(Role.USER).isActive(true).build());
        String title = "점검 안내 " + System.nanoTime();

        AdminNotificationSendResponse sent = adminNotificationService.notifyUser(admin.getUserId(), member.getUserId(),
                new AdminNotificationRequest(title, "내일 점검합니다.", NotificationType.SYSTEM, true, today.plusDays(14)));
        Long noticeId = sent.noticeId();
        entityManager.flush();

        AdminSearchConditionDto byTitle = AdminSearchConditionDto.of(title, AdminNoticeSearchField.TITLE, AdminSearchMatchType.EXACT);
        assertThat(adminNoticeService.getNotices(byTitle, true, PageRequest.of(0, 20)).getContent())
                .extracting(AdminNoticeResponse::noticeId).containsExactly(noticeId);
        assertThat(adminNoticeService.getNotices(byTitle, false, PageRequest.of(0, 20)).getContent()).isEmpty();

        AdminNoticeResponse detail = adminNoticeService.getNoticeDetail(noticeId);
        assertThat(detail.recipientCount()).isEqualTo(1L);
        assertThat(detail.readCount()).isZero();
        assertThat(detail.popupActive()).isTrue();
        assertThat(detail.createdByLoginId()).isEqualTo(admin.getLoginId());

        assertThat(adminNoticeService.changePopupEndDate(admin.getUserId(), noticeId, today.plusDays(30)).popupEndDate())
                .isEqualTo(today.plusDays(30));
        assertThatThrownBy(() -> adminNoticeService.changePopupEndDate(admin.getUserId(), noticeId, today.minusDays(1)))
                .isInstanceOf(CustomException.class);

        AdminNoticeResponse ended = adminNoticeService.endPopup(admin.getUserId(), noticeId);
        assertThat(ended.popupActive()).isFalse();
        assertThat(ended.popup()).isTrue();
        entityManager.flush();
        assertThat(notificationRepository.findActivePopups(member.getUserId(), today)).isEmpty();

        adminNoticeService.deleteNotice(admin.getUserId(), noticeId);
        entityManager.flush();
        entityManager.clear();
        assertThat(noticeRepository.findById(noticeId)).isEmpty();
        assertThat(notificationRepository.countByNoticeId(noticeId)).isZero();
        assertThat(notificationRepository.findAllByUserIdOrderByCreatedAtDesc(member.getUserId())).isEmpty();
    }
}
