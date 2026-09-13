package com.teamfp.aistock.domain.auth.service;

import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.auth.dto.request.SignupRequest;
import com.teamfp.aistock.domain.auth.dto.response.SignupResponse;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.SocialAccountRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisAuthCodeService;
import com.teamfp.aistock.global.redis.RedisTokenService;
import com.teamfp.aistock.global.security.JwtProvider;
import com.teamfp.aistock.infra.mail.MailClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String ADMIN_SIGNUP_CODE = "ADMIN-SECRET-CODE";

    @Mock
    private UserRepository userRepository;
    @Mock
    private SocialAccountRepository socialAccountRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private RedisTokenService redisTokenService;
    @Mock
    private RedisAuthCodeService redisAuthCodeService;
    @Mock
    private MailClient mailClient;
    @Mock
    private AccountService accountService;

    private AuthService authService;

    @Test
    void recoveryRejectsCodeBeforeLookingUpIdentity() {
        var request = new com.teamfp.aistock.domain.auth.dto.request.AccountRecoveryRequest(
                "tester01", "테스터", "tester01@example.com", LocalDate.of(2000, 1, 1), "000000", "newpass123");
        assertThatThrownBy(() -> authService.findLoginId(request)).isInstanceOf(CustomException.class);
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void passwordRecoveryUpdatesHashAndRevokesRefreshToken() {
        var request = new com.teamfp.aistock.domain.auth.dto.request.AccountRecoveryRequest(
                "tester01", "테스터", "tester01@example.com", null, "123456", "newpass123");
        var user = User.builder().loginId("tester01").name("테스터").email("tester01@example.com")
                .password("oldHash").isActive(true).build();
        ReflectionTestUtils.setField(user, "userId", 10L);
        given(redisAuthCodeService.verifyAndDeleteEmailCode(request.email(), request.code())).willReturn(true);
        given(userRepository.findByEmail(request.email())).willReturn(java.util.Optional.of(user));
        given(passwordEncoder.encode(request.newPassword())).willReturn("newHash");
        authService.resetPassword(request);
        assertThat(user.getPassword()).isEqualTo("newHash");
        verify(redisTokenService).deleteRefreshToken(10L);
    }

    @Test
    void duplicateIdCheckUsesDatabaseAndValidatesInput() {
        given(userRepository.existsByLoginId("tester01")).willReturn(true);
        assertThat(authService.checkLoginId("tester01").available()).isFalse();
        assertThat(authService.checkLoginId("unused01").available()).isTrue();
        assertThatThrownBy(() -> authService.checkLoginId("!")).isInstanceOf(CustomException.class);
    }

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                socialAccountRepository,
                passwordEncoder,
                jwtProvider,
                redisTokenService,
                redisAuthCodeService,
                mailClient,
                accountService,
                List.of(),
                ADMIN_SIGNUP_CODE
        );
    }

    private SignupRequest createSignupRequest(Role role, String adminCode) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "loginId", "tester01");
        ReflectionTestUtils.setField(request, "password", "raw-password");
        ReflectionTestUtils.setField(request, "name", "테스터");
        ReflectionTestUtils.setField(request, "email", "tester01@example.com");
        ReflectionTestUtils.setField(request, "birthdate", LocalDate.of(2000, 1, 1));
        ReflectionTestUtils.setField(request, "role", role);
        ReflectionTestUtils.setField(request, "adminCode", adminCode);
        return request;
    }

    // save() 호출 시 실제 IDENTITY 전략(Hibernate가 persist 시 엔티티에 PK를 채워주는 동작)을
    // Mockito로 흉내낸다. User는 @Setter가 없으므로 ReflectionTestUtils로 userId를 채운다.
    private void stubUserSaveWithGeneratedId(Long generatedId) {
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User savedUser = invocation.getArgument(0);
            ReflectionTestUtils.setField(savedUser, "userId", generatedId);
            return savedUser;
        });
    }

    @Nested
    @DisplayName("signup()")
    class Signup {

        @Test
        @DisplayName("일반 유저로 정상 가입하면 계좌가 자동 생성되고 SignupResponse를 반환한다")
        void signup_success_createsAccountAndReturnsResponse() {
            SignupRequest request = createSignupRequest(Role.USER, null);
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(false);
            given(userRepository.existsByEmail(request.getEmail())).willReturn(false);
            given(redisAuthCodeService.consumeEmailVerified(request.getEmail())).willReturn(true);
            given(passwordEncoder.encode(request.getPassword())).willReturn("encoded-password");
            stubUserSaveWithGeneratedId(1L);
            given(accountService.createAccount(eq(1L), any(CreateAccountRequest.class)))
                    .willReturn(new AccountInfoResponse(10L, "기본 계좌", "VA0000000000",
                            10_000_000L, 0L, 10_000_000L, 0, AccountStatus.ACTIVE));

            SignupResponse response = authService.signup(request);

            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getLoginId()).isEqualTo("tester01");
            assertThat(response.getRole()).isEqualTo(Role.USER);

            ArgumentCaptor<CreateAccountRequest> accountRequestCaptor = ArgumentCaptor.forClass(CreateAccountRequest.class);
            verify(accountService).createAccount(eq(1L), accountRequestCaptor.capture());
            assertThat(accountRequestCaptor.getValue().accountName()).isEqualTo("기본 계좌");
        }

        @Test
        @DisplayName("아이디가 이미 존재하면 DUPLICATE_LOGIN_ID 예외를 던진다")
        void signup_duplicateLoginId_throwsException() {
            SignupRequest request = createSignupRequest(Role.USER, null);
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(true);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);

            verify(userRepository, never()).save(any());
            verify(accountService, never()).createAccount(anyLong(), any());
        }

        @Test
        @DisplayName("이메일이 이미 존재하면 DUPLICATE_EMAIL 예외를 던진다")
        void signup_duplicateEmail_throwsException() {
            SignupRequest request = createSignupRequest(Role.USER, null);
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(false);
            given(userRepository.existsByEmail(request.getEmail())).willReturn(true);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("role=ADMIN인데 adminCode가 일치하지 않으면 INVALID_ADMIN_CODE 예외를 던진다")
        void signup_adminRoleWithWrongCode_throwsException() {
            SignupRequest request = createSignupRequest(Role.ADMIN, "wrong-code");
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(false);
            given(userRepository.existsByEmail(request.getEmail())).willReturn(false);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_ADMIN_CODE);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("existsByLoginId 체크 통과 후 save()에서 UNIQUE 제약 위반(동시 가입 경합)이 나면 원인을 재조회해 DUPLICATE_LOGIN_ID를 던진다")
        void signup_raceOnSave_duplicateLoginId_throwsException() {
            SignupRequest request = createSignupRequest(Role.USER, null);
            given(userRepository.existsByLoginId(request.getLoginId()))
                    .willReturn(false)  // 최초 체크 시점엔 아직 미존재
                    .willReturn(true);  // save() 실패 후 재조회 시점엔 경합 상대가 먼저 커밋됨
            given(userRepository.existsByEmail(request.getEmail())).willReturn(false);
            given(redisAuthCodeService.consumeEmailVerified(request.getEmail())).willReturn(true);
            given(passwordEncoder.encode(request.getPassword())).willReturn("encoded-password");
            given(userRepository.save(any(User.class)))
                    .willThrow(new DataIntegrityViolationException("uk_login_id violated"));

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);

            verify(accountService, never()).createAccount(anyLong(), any());
        }

        @Test
        @DisplayName("existsByEmail 체크 통과 후 save()에서 UNIQUE 제약 위반(동시 가입 경합)이 나면 원인을 재조회해 DUPLICATE_EMAIL을 던진다")
        void signup_raceOnSave_duplicateEmail_throwsException() {
            SignupRequest request = createSignupRequest(Role.USER, null);
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(false);
            given(userRepository.existsByEmail(request.getEmail())).willReturn(false);
            given(redisAuthCodeService.consumeEmailVerified(request.getEmail())).willReturn(true);
            given(passwordEncoder.encode(request.getPassword())).willReturn("encoded-password");
            given(userRepository.save(any(User.class)))
                    .willThrow(new DataIntegrityViolationException("uk_email violated"));

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

            verify(accountService, never()).createAccount(anyLong(), any());
        }

        @Test
        @DisplayName("이메일 인증을 거치지 않았으면(consumeEmailVerified=false) EMAIL_NOT_VERIFIED 예외를 던진다")
        void signup_emailNotVerified_throwsException() {
            SignupRequest request = createSignupRequest(Role.USER, null);
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(false);
            given(userRepository.existsByEmail(request.getEmail())).willReturn(false);
            given(redisAuthCodeService.consumeEmailVerified(request.getEmail())).willReturn(false);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

            verify(userRepository, never()).save(any());
            verify(accountService, never()).createAccount(anyLong(), any());
        }

        @Test
        @DisplayName("role=ADMIN이고 adminCode가 ADMIN_SIGNUP_CODE와 일치하면 ADMIN으로 가입된다")
        void signup_adminRoleWithCorrectCode_success() {
            SignupRequest request = createSignupRequest(Role.ADMIN, ADMIN_SIGNUP_CODE);
            given(userRepository.existsByLoginId(request.getLoginId())).willReturn(false);
            given(userRepository.existsByEmail(request.getEmail())).willReturn(false);
            given(redisAuthCodeService.consumeEmailVerified(request.getEmail())).willReturn(true);
            given(passwordEncoder.encode(anyString())).willReturn("encoded-password");
            stubUserSaveWithGeneratedId(2L);
            given(accountService.createAccount(eq(2L), any(CreateAccountRequest.class)))
                    .willReturn(new AccountInfoResponse(11L, "기본 계좌", "VA0000000001",
                            10_000_000L, 0L, 10_000_000L, 0, AccountStatus.ACTIVE));

            SignupResponse response = authService.signup(request);

            assertThat(response.getRole()).isEqualTo(Role.ADMIN);
        }
    }

    @Nested
    @DisplayName("sendEmailCode()")
    class SendEmailCode {

        @Test
        @DisplayName("6자리 인증코드를 생성해 Redis에 저장하고 메일로 발송한다")
        void sendEmailCode_savesAndSendsSixDigitCode() {
            String email = "tester01@example.com";

            authService.sendEmailCode(email);

            ArgumentCaptor<String> redisCodeCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> mailCodeCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisAuthCodeService).saveEmailCode(eq(email), redisCodeCaptor.capture());
            verify(mailClient).sendAuthCode(eq(email), mailCodeCaptor.capture());

            assertThat(redisCodeCaptor.getValue()).matches("\\d{6}");
            assertThat(mailCodeCaptor.getValue()).isEqualTo(redisCodeCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("verifyEmailCode()")
    class VerifyEmailCode {

        @Test
        @DisplayName("코드가 일치하면 예외 없이 통과한다")
        void verifyEmailCode_matched_doesNotThrow() {
            given(redisAuthCodeService.verifyAndDeleteEmailCode("tester01@example.com", "123456"))
                    .willReturn(true);

            authService.verifyEmailCode("tester01@example.com", "123456");

            verify(redisAuthCodeService).verifyAndDeleteEmailCode("tester01@example.com", "123456");
            verify(redisAuthCodeService).markEmailVerified("tester01@example.com");
        }

        @Test
        @DisplayName("코드가 일치하지 않거나 만료되었으면 EMAIL_CODE_MISMATCH 예외를 던진다")
        void verifyEmailCode_notMatched_throwsException() {
            given(redisAuthCodeService.verifyAndDeleteEmailCode(anyString(), anyString()))
                    .willReturn(false);

            assertThatThrownBy(() -> authService.verifyEmailCode("tester01@example.com", "000000"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EMAIL_CODE_MISMATCH);

            verify(redisAuthCodeService, never()).markEmailVerified(anyString());
        }
    }
}
