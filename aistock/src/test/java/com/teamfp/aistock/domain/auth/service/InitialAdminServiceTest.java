package com.teamfp.aistock.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.teamfp.aistock.domain.admin.service.AuditLogService;
import com.teamfp.aistock.domain.auth.dto.request.InitialAdminCreateRequest;
import com.teamfp.aistock.domain.auth.dto.response.SignupResponse;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisAuthCodeService;

@ExtendWith(MockitoExtension.class)
class InitialAdminServiceTest {

    private static final String SETUP_CODE = "SETUP-CODE-123";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RedisAuthCodeService redisAuthCodeService;

    private InitialAdminService initialAdminService;

    @BeforeEach
    void setUp() {
        initialAdminService = service(SETUP_CODE);
    }

    @Test
    @DisplayName("관리자가 없으면 adminExists=false")
    void statusWithoutAdmin() {
        given(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).willReturn(false);

        assertThat(initialAdminService.getStatus().adminExists()).isFalse();
    }

    @Test
    @DisplayName("관리자가 0명이고 인증 코드가 맞으면 ADMIN으로 만들고 감사 로그를 남긴다")
    void createsInitialAdmin() {
        given(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).willReturn(false);
        given(passwordEncoder.encode("pass1234")).willReturn("encoded");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "userId", 1L);
            return saved;
        });

        SignupResponse response = initialAdminService.createInitialAdmin(request(SETUP_CODE));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded");
        assertThat(response.getRole()).isEqualTo(Role.ADMIN);
        assertThat(response.getUserId()).isEqualTo(1L);
        verify(auditLogService).record(eq(1L), eq(AuditLogService.ACTION_ADMIN_CREATE), eq(AuditLogService.TARGET_ADMIN),
                eq(1L), eq(null), eq("firstadmin"), eq(InitialAdminService.AUDIT_REASON));
    }

    @Test
    @DisplayName("관리자가 이미 있으면 코드가 맞아도 INITIAL_ADMIN_ALREADY_EXISTS")
    void rejectsWhenAdminExists() {
        given(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).willReturn(true);

        assertErrorCode(() -> initialAdminService.createInitialAdmin(request(SETUP_CODE)), ErrorCode.INITIAL_ADMIN_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("인증 코드가 틀리면 INVALID_ADMIN_CODE")
    void rejectsWrongCode() {
        given(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).willReturn(false);

        assertErrorCode(() -> initialAdminService.createInitialAdmin(request("wrong")), ErrorCode.INVALID_ADMIN_CODE);
        verify(userRepository, never()).save(any());
        verify(auditLogService, never()).record(anyLong(), anyString(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("서버에 인증 코드가 설정되지 않았으면 아무 코드로도 만들 수 없다")
    void rejectsWhenCodeNotConfigured() {
        InitialAdminService unconfigured = service("");
        given(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).willReturn(false);

        assertErrorCode(() -> unconfigured.createInitialAdmin(request("")), ErrorCode.INVALID_ADMIN_CODE);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("아이디가 이미 있으면 DUPLICATE_LOGIN_ID")
    void rejectsDuplicateLoginId() {
        given(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).willReturn(false);
        given(userRepository.existsByLoginId("firstadmin")).willReturn(true);

        assertErrorCode(() -> initialAdminService.createInitialAdmin(request(SETUP_CODE)), ErrorCode.DUPLICATE_LOGIN_ID);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("인증 코드를 틀리면 틀린 횟수를 올리고, 3번째 틀리면 ADMIN_CODE_LOCKED")
    void wrongCodeCountsAndLocksOnThird() {
        given(redisAuthCodeService.incrementAdminCodeFail(InitialAdminService.INITIAL_ADMIN_LOCK_SUBJECT)).willReturn(1L, 3L);

        assertErrorCode(() -> initialAdminService.verifyAdminCode(InitialAdminService.INITIAL_ADMIN_LOCK_SUBJECT, "wrong"),
                ErrorCode.INVALID_ADMIN_CODE);
        assertErrorCode(() -> initialAdminService.verifyAdminCode(InitialAdminService.INITIAL_ADMIN_LOCK_SUBJECT, "wrong"),
                ErrorCode.ADMIN_CODE_LOCKED);
        verify(redisAuthCodeService, never()).resetAdminCodeFail(anyString());
    }

    @Test
    @DisplayName("잠금 중이면 맞는 코드를 넣어도 ADMIN_CODE_LOCKED, 횟수는 더 올리지 않는다")
    void lockedRejectsEvenCorrectCode() {
        given(redisAuthCodeService.isAdminCodeLocked("user:9")).willReturn(true);

        assertErrorCode(() -> initialAdminService.verifyAdminCode("user:9", SETUP_CODE), ErrorCode.ADMIN_CODE_LOCKED);
        verify(redisAuthCodeService, never()).incrementAdminCodeFail(anyString());
    }

    @Test
    @DisplayName("맞는 코드면 틀린 횟수를 초기화한다")
    void correctCodeResetsFailures() {
        initialAdminService.verifyAdminCode("user:9", SETUP_CODE);

        verify(redisAuthCodeService).resetAdminCodeFail("user:9");
    }

    private InitialAdminService service(String setupCode) {
        return new InitialAdminService(userRepository, passwordEncoder, auditLogService, transactionManager,
                redisAuthCodeService, setupCode);
    }

    private InitialAdminCreateRequest request(String setupCode) {
        return new InitialAdminCreateRequest("firstadmin", "pass1234", "최초관리자", "first@example.com", setupCode);
    }

    private void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(errorCode);
    }
}
