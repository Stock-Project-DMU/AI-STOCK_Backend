package com.teamfp.aistock.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamfp.aistock.domain.admin.service.AuditLogService;
import com.teamfp.aistock.domain.auth.dto.request.InitialAdminCreateRequest;
import com.teamfp.aistock.domain.auth.dto.response.InitialAdminStatusResponse;
import com.teamfp.aistock.domain.auth.dto.response.SignupResponse;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisAuthCodeService;

/**
 * 최초 관리자 계정 생성(feat/admin-improvements). 관리자 페이지의 관리자 생성은 이미 관리자가 있어야 쓸 수 있어,
 * 관리자가 한 명도 없을 때(탈퇴하지 않은 ADMIN 0명 — 마지막 관리자가 계정을 폐기한 뒤 포함)만 로그인 없이 첫 관리자를
 * 만들 수 있게 한다.
 * - 관리자 인증 코드(setupCode)가 서버 설정값 admin.signup-code(ADMIN_SIGNUP_CODE)와 같아야 한다 — 설정값이
 *   비어 있으면 아무도 만들 수 없다. 배포 직후 관리자가 0명인 틈에 아무나 관리자가 되는 것을 막는다.
 * - 동시에 여러 요청이 와도 한 명만 성공한다 — "0명인지 확인 → 저장 → 커밋"을 한 번에 한 요청씩만 실행한다
 *   (synchronized + 그 안에서 트랜잭션을 열고 닫음. 서버 1대 기준).
 * - 생성 기록을 감사 로그(ADMIN_CREATE)에 남긴다. 처리 관리자는 만들어진 관리자 자신이다.
 * - 인증 코드를 3번 틀리면 10분 동안 잠근다(verifyAdminCode, Redis auth:admin_code_fail:*). 최초 관리자 생성은 로그인 전
 *   요청이라 IP로 셀 수도 있지만, 서버가 프록시 뒤에서 실제 IP를 알지 못해 시도한 사람을 구분하지 않고 합쳐서 센다.
 */
@Service
public class InitialAdminService {

    static final String AUDIT_REASON = "최초 관리자 계정 생성";
    // 최초 관리자 생성의 인증 코드 틀린 횟수 기준 — 누가 시도하든 합쳐서 센다
    static final String INITIAL_ADMIN_LOCK_SUBJECT = "initial";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;
    private final RedisAuthCodeService redisAuthCodeService;
    private final String setupCode;

    public InitialAdminService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager,
            RedisAuthCodeService redisAuthCodeService,
            @Value("${admin.signup-code}") String setupCode
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.redisAuthCodeService = redisAuthCodeService;
        this.setupCode = setupCode;
    }

    @Transactional(readOnly = true)
    public InitialAdminStatusResponse getStatus() {
        return new InitialAdminStatusResponse(adminExists());
    }

    public synchronized SignupResponse createInitialAdmin(InitialAdminCreateRequest request) {
        return transactionTemplate.execute(status -> {
            if (adminExists()) {
                throw new CustomException(ErrorCode.INITIAL_ADMIN_ALREADY_EXISTS);
            }
            verifyAdminCode(INITIAL_ADMIN_LOCK_SUBJECT, request.setupCode());
            if (userRepository.existsByLoginId(request.loginId())) {
                throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
            }
            if (userRepository.existsByEmail(request.email())) {
                throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
            }

            User admin = User.builder()
                    .loginId(request.loginId())
                    .password(passwordEncoder.encode(request.password()))
                    .name(request.name())
                    .email(request.email())
                    .role(Role.ADMIN)
                    .isActive(true)
                    .build();
            try {
                userRepository.save(admin);
            } catch (DataIntegrityViolationException e) {
                // 일반 회원가입과 동시에 같은 아이디/이메일이 저장되는 경합 — AuthService.signup()과 같은 방식으로 원인을 가린다.
                if (userRepository.existsByLoginId(request.loginId())) {
                    throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
                }
                throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
            }

            auditLogService.record(admin.getUserId(), AuditLogService.ACTION_ADMIN_CREATE, AuditLogService.TARGET_ADMIN,
                    admin.getUserId(), null, admin.getLoginId(), AUDIT_REASON);

            return SignupResponse.builder()
                    .userId(admin.getUserId())
                    .loginId(admin.getLoginId())
                    .role(admin.getRole())
                    .build();
        });
    }

    private boolean adminExists() {
        return userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN);
    }

    /**
     * 관리자 인증 코드 확인 — 최초 관리자 생성과 관리자 계정 폐기(UserWithdrawalService)에서 쓴다. lockSubject 기준으로
     * 이미 3번 틀렸으면(10분 잠금 중) 코드를 보지도 않고 ADMIN_CODE_LOCKED. 틀리면 횟수를 올리고 1·2번째는
     * INVALID_ADMIN_CODE, 3번째는 ADMIN_CODE_LOCKED. 맞으면 횟수를 초기화한다.
     */
    public void verifyAdminCode(String lockSubject, String input) {
        if (redisAuthCodeService.isAdminCodeLocked(lockSubject)) {
            throw new CustomException(ErrorCode.ADMIN_CODE_LOCKED);
        }
        if (!matchesSetupCode(input)) {
            long failures = redisAuthCodeService.incrementAdminCodeFail(lockSubject);
            throw new CustomException(failures >= RedisAuthCodeService.MAX_ADMIN_CODE_FAIL
                    ? ErrorCode.ADMIN_CODE_LOCKED : ErrorCode.INVALID_ADMIN_CODE);
        }
        redisAuthCodeService.resetAdminCodeFail(lockSubject);
    }

    // 서버 설정값이 비어 있으면 항상 false. 길이·내용에 따라 비교 시간이 달라지지 않게 MessageDigest.isEqual로 비교한다.
    private boolean matchesSetupCode(String input) {
        if (setupCode == null || setupCode.isBlank() || input == null) {
            return false;
        }
        return MessageDigest.isEqual(setupCode.getBytes(StandardCharsets.UTF_8), input.getBytes(StandardCharsets.UTF_8));
    }
}
