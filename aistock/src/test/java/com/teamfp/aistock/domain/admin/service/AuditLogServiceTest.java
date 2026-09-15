package com.teamfp.aistock.domain.admin.service;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.admin.entity.AuditLog;
import com.teamfp.aistock.domain.admin.repository.AuditLogRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserRepository userRepository;

    private AuditLogService auditLogService;

    private static final Long ADMIN_ID = 999L;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, userRepository);
    }

    @AfterEach
    void tearDown() {
        // SecurityContextHolder는 ThreadLocal이라 테스트 간에 값이 새어나가지 않도록 매번 비운다.
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("record()는 관리자 loginId를 스냅샷으로 채워 저장한다")
    void record_snapshotsAdminLoginId() {
        User admin = User.builder().loginId("admin01").name("관리자").role(Role.ADMIN).isActive(true).build();
        ReflectionTestUtils.setField(admin, "userId", ADMIN_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        auditLogService.record(ADMIN_ID, AuditLogService.ACTION_USER_STATUS_CHANGE, AuditLogService.TARGET_USER,
                1L, "ACTIVE", "SUSPENDED", "이상 거래 의심");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAdminUserId()).isEqualTo(ADMIN_ID);
        assertThat(saved.getAdminLoginId()).isEqualTo("admin01");
        assertThat(saved.getAction()).isEqualTo(AuditLogService.ACTION_USER_STATUS_CHANGE);
        assertThat(saved.getTargetType()).isEqualTo(AuditLogService.TARGET_USER);
        assertThat(saved.getTargetId()).isEqualTo(1L);
        assertThat(saved.getBeforeValue()).isEqualTo("ACTIVE");
        assertThat(saved.getAfterValue()).isEqualTo("SUSPENDED");
        assertThat(saved.getReason()).isEqualTo("이상 거래 의심");
    }

    @Test
    @DisplayName("record()는 관리자를 못 찾아도(탈퇴 등) \"unknown\"으로 저장하고 예외를 던지지 않는다")
    void record_unknownAdmin_doesNotThrow() {
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

        auditLogService.record(ADMIN_ID, AuditLogService.ACTION_ORDER_CANCEL, AuditLogService.TARGET_ORDER,
                1L, "PENDING", "CANCELLED", "사유");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAdminLoginId()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("record()는 인증 컨텍스트가 없으면 requestIp를 null로 남긴다")
    void record_noAuthenticationContext_leavesRequestIpNull() {
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

        auditLogService.record(ADMIN_ID, AuditLogService.ACTION_ORDER_CANCEL, AuditLogService.TARGET_ORDER,
                1L, "PENDING", "CANCELLED", "사유");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestIp()).isNull();
    }

    @Test
    @DisplayName("record()는 SecurityContextHolder에 담긴 WebAuthenticationDetails에서 요청 IP를 꺼내 채운다(코드리뷰 반영)")
    void record_fillsRequestIpFromSecurityContext() {
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

        WebAuthenticationDetails details = new WebAuthenticationDetails("203.0.113.10", null);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("admin01", null, java.util.List.of());
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        auditLogService.record(ADMIN_ID, AuditLogService.ACTION_ORDER_CANCEL, AuditLogService.TARGET_ORDER,
                1L, "PENDING", "CANCELLED", "사유");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestIp()).isEqualTo("203.0.113.10");
    }

    @Test
    @DisplayName("getDetail()은 존재하지 않는 auditLogId면 AUDIT_LOG_NOT_FOUND 예외를 던진다")
    void getDetail_notFound() {
        when(auditLogRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auditLogService.getDetail(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUDIT_LOG_NOT_FOUND);
    }
}
