package com.teamfp.aistock.domain.admin.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserListResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminWithdrawnUserResponse;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.util.CsvWriter;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 사용자 목록·상세 조회 및 활성상태 변경. admin 도메인은 자체 Entity/Repository를 두지
 * 않고 user/account/order 도메인의 Repository·Service를 그대로 주입받아 조합한다
 * (CLAUDE.md 4번). holdings 조회는 HoldingRepository를 직접 쓰지 않고 order 도메인의
 * HoldingValuationService를 거친다 — 시세 캐시 조회·평단가 폴백 로직을 여기서 중복 구현하지
 * 않기 위함이다(HoldingValuationService 클래스 주석 참고).
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final OrderRepository orderRepository;
    private final HoldingValuationService holdingValuationService;

    // 회원 검색·필터(ADMIN_API_BACKEND_HANDOFF.md 3.2). query/status/role이 전부 비어 있으면
    // 기존 findAllByIsActiveTrue(pageable)와 동일하게 전체 목록을 반환한다 — searchUsers()가
    // null 파라미터를 "조건 없음"으로 처리하므로 별도 분기가 필요 없다. 탈퇴(deactivate)한
    // 유저는 searchUsers() 쿼리 자체가 isActive=true로 걸러 목록에서 제외한다.
    @Transactional(readOnly = true)
    public Page<AdminUserListResponse> getUsers(String query, UserStatus status, Role role, Pageable pageable) {
        return userRepository.searchUsers(blankToNull(query), status, role, pageable).map(AdminUserListResponse::from);
    }

    // 빈 문자열은 "조건 없음"으로 취급한다(3.2 요구사항) — searchUsers()의 "query is null" 분기를
    // 그대로 타게 하기 위해 컨트롤러에서 넘어온 빈 문자열을 여기서 null로 정규화한다.
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static final List<String> USER_CSV_HEADERS = List.of("회원번호", "아이디", "이름", "이메일", "역할", "상태", "가입일");

    /**
     * 회원 목록 CSV 내보내기(ADMIN_API_BACKEND_HANDOFF.md 6.2). 화면과 동일한 검색·필터 조건을
     * 그대로 받아 searchUsers()에 넘기되, 전역 설정(application.yml의
     * spring.data.web.pageable.max-page-size=100)에 걸리지 않도록 요청 파라미터로 만들어진
     * Pageable이 아니라 이 메서드 안에서 직접 만든 "충분히 큰" PageRequest를 쓴다 — 그 설정은
     * HTTP 쿼리 파라미터(size=...)를 해석할 때만 적용되고, 코드에서 직접 만든 PageRequest에는
     * 적용되지 않는다.
     */
    @Transactional(readOnly = true)
    public byte[] exportUsersCsv(String query, UserStatus status, Role role, Sort sort) {
        List<User> users = userRepository.searchUsers(blankToNull(query), status, role, PageRequest.of(0, Integer.MAX_VALUE, sort))
                .getContent();
        List<List<String>> rows = users.stream()
                .map(u -> List.of(
                        String.valueOf(u.getUserId()),
                        u.getLoginId() == null ? "" : u.getLoginId(),
                        u.getName(),
                        u.getEmail() == null ? "" : u.getEmail(),
                        u.getRole().name(),
                        u.getStatus().name(),
                        u.getCreatedAt().toString()
                ))
                .toList();
        return CsvWriter.write(USER_CSV_HEADERS, rows);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUserDetail(Long userId) {
        return buildDetail(findUser(userId));
    }

    /**
     * 관리자 신규 생성(ADMIN_API_BACKEND_HANDOFF.md 5.1, "구현 전 결정이 필요한 정책" 6번 —
     * 정책 확정 전 "별도 생성 방식"으로 우선 구현). AuthService.signup()과 동일한 패턴으로
     * 중복 아이디/이메일을 먼저 걸러내고, save() 시점에 동시 가입 경합으로 UNIQUE 제약을
     * 위반하면 같은 트랜잭션에서 재조회해 원인을 가려 DUPLICATE_*로 변환한다.
     */
    @Transactional
    public AdminUserListResponse createAdmin(Long adminUserId, AdminCreateRequest request) {
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
            if (userRepository.existsByLoginId(request.loginId())) {
                throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
            }
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        auditLogService.record(adminUserId, AuditLogService.ACTION_ADMIN_CREATE, AuditLogService.TARGET_ADMIN,
                admin.getUserId(), null, admin.getLoginId(), "관리자 계정 생성");

        return AdminUserListResponse.from(admin);
    }

    // 탈퇴 회원 조회(ADMIN_API_BACKEND_HANDOFF.md 5.4 옵션2). 정책이 아직 팀에서 확정되지 않아
    // 잠정적으로 만들어둔 API — 최종 정책이 다르게 정해지면(예: 완전 제외) 이 메서드와
    // AdminUserController의 대응 엔드포인트를 함께 제거하면 된다.
    @Transactional(readOnly = true)
    public Page<AdminWithdrawnUserResponse> getWithdrawnUsers(Pageable pageable) {
        return userRepository.findAllByIsActiveFalse(pageable).map(AdminWithdrawnUserResponse::from);
    }

    // 관리자 계정 관리(ADMIN_API_BACKEND_HANDOFF.md 5.1) — GET /api/admin/admins/{adminId}용.
    // getUserDetail()과 조회 로직 자체는 같지만, "관리자 전용 목록에서 이 id를 찾을 수 없음"이
    // 정확한 의미가 되도록 role이 ADMIN이 아니면 일반 유저 상세와 구분 없이 USER_NOT_FOUND로
    // 막는다 — 예를 들어 일반 회원의 userId를 이 경로로 넣었을 때 상세가 그대로 보이면
    // "관리자 목록"이라는 URL의 의미와 어긋난다.
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getAdminDetail(Long adminId) {
        User admin = findUser(adminId);
        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        return buildDetail(admin);
    }

    /**
     * 관리자 계정 관리(5.1) — PATCH /api/admin/admins/{adminId}/status용. getAdminDetail()과
     * 동일하게 role이 ADMIN이 아니면 USER_NOT_FOUND로 막은 뒤 updateUserStatus()에 위임한다
     * (코드리뷰 반영, 2026-09) — updateUserStatus() 자체는 역할과 무관하게 모든 유저를 대상으로
     * 하므로(PATCH /api/admin/users/{userId}/status가 의도적으로 그렇게 동작함), 이 메서드가
     * "관리자 계정 관리" 엔드포인트의 대상 범위를 관리자로 좁히는 책임을 진다 — 그렇지 않으면
     * 일반 회원의 userId를 이 경로에 넣어도 조용히 상태가 바뀌어버려, GET(목록/상세)은
     * 관리자로 범위를 좁혀놓고 PATCH만 그 경계가 빠지는 불일치가 생긴다.
     */
    @Transactional
    public AdminUserDetailResponse updateAdminStatus(Long adminUserId, Long adminId, AdminUserStatusRequest request) {
        User admin = findUser(adminId);
        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        return updateUserStatus(adminUserId, adminId, request);
    }

    @Transactional
    public AdminUserDetailResponse updateUserStatus(Long adminUserId, Long userId, AdminUserStatusRequest request) {
        User user = findUser(userId);
        UserStatus beforeStatus = user.getStatus();
        if (request.status() == UserStatus.SUSPENDED) {
            suspend(adminUserId, user);
        } else {
            user.activate();
        }
        auditLogService.record(adminUserId, AuditLogService.ACTION_USER_STATUS_CHANGE, AuditLogService.TARGET_USER,
                userId, beforeStatus.name(), user.getStatus().name(), null);
        return buildDetail(user);
    }

    /**
     * 정지 처리. 이미 SUSPENDED인 유저에게 다시 SUSPENDED를 보내는 요청(타임아웃 후 재시도 등)은
     * 상태 변화가 없는 멱등한 요청이므로, 그대로 통과시키고 아래 lockout 가드도 다시 태우지
     * 않는다 — "활성 admin이 이 유저 하나뿐이라 정지 못 함" 같은 오해의 소지가 있는 예외를
     * 이미 정지된 상태에 대해 또 던지지 않기 위함이다.
     */
    private void suspend(Long adminUserId, User targetUser) {
        if (targetUser.getStatus() == UserStatus.SUSPENDED) {
            return;
        }
        validateSuspendable(adminUserId, targetUser);
        targetUser.suspend();
    }

    /**
     * 정지 가능 여부 검증. 다음 두 경우를 막지 않으면 관리자 전원이 /api/admin/** 밖으로
     * 밀려나 DB를 직접 고치지 않는 한 아무도 되돌릴 수 없는 lockout 상태가 될 수 있다.
     * 1) 관리자가 자기 자신을 정지시키는 경우 — 요청을 보낸 본인이 다음 요청부터 바로 막힌다.
     * 2) 활성 상태인 마지막 ADMIN을 정지시키는 경우 — 남은 관리자가 0명이 되어 아무도
     *    /api/admin/users/{userId}/status로 되돌릴 수 없다. 두 번째 검사는 활성 ADMIN 행들에
     *    비관적 락을 걸어(UserRepository.findAllByRoleAndStatusAndIsActiveTrueForUpdate) 서로
     *    다른 admin을 동시에 정지시키는 두 요청이 락 없이 각자 "정지 전 카운트"를 보고 둘 다
     *    통과해버리는 경쟁 상태를 막는다.
     */
    private void validateSuspendable(Long adminUserId, User targetUser) {
        if (targetUser.getUserId().equals(adminUserId)) {
            throw new CustomException(ErrorCode.SELF_STATUS_CHANGE_NOT_ALLOWED);
        }
        if (targetUser.getRole() == Role.ADMIN) {
            List<User> activeAdmins = userRepository.findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role.ADMIN, UserStatus.ACTIVE);
            if (activeAdmins.size() <= 1) {
                throw new CustomException(ErrorCode.LAST_ADMIN_SUSPEND_NOT_ALLOWED);
            }
        }
    }

    /**
     * 유저가 가진 계좌(A/B/C, 최대 3개)를 전부 조회해 accounts는 리스트 그대로,
     * holdings/orders는 계좌별로 나눠 조회하지 않고 IN 절 배치 조회로 한 번에 합쳐서
     * 보여준다(NAMING.md 8-17 참고). orders는 배치 조회 쿼리 자체에서 orderedAt
     * 내림차순으로 정렬되므로 애플리케이션 레벨에서 다시 정렬할 필요가 없다.
     */
    private AdminUserDetailResponse buildDetail(User user) {
        List<Account> accounts = accountRepository.findAllByUserId(user.getUserId());
        if (accounts.isEmpty()) {
            return AdminUserDetailResponse.of(user, List.of(), List.of(), List.of());
        }

        List<AccountInfoResponse> accountResponses = accounts.stream()
                .map(AccountInfoResponse::from)
                .toList();

        List<Long> accountIds = accounts.stream().map(Account::getAccountId).toList();

        List<HoldingResponse> holdings = holdingValuationService.getHoldingValuations(accountIds).stream()
                .map(HoldingResponse::of)
                .toList();

        List<OrderHistoryResponse> orders = orderRepository.findAllByAccountIdInOrderByOrderedAtDesc(accountIds).stream()
                .map(OrderHistoryResponse::from)
                .toList();

        return AdminUserDetailResponse.of(user, accountResponses, holdings, orders);
    }

    // 탈퇴(deactivate) 유저는 목록뿐 아니라 상세 조회·상태변경에서도 막는다 — getUsers()의
    // findAllByIsActiveTrue와 동일한 기준(isActive)으로 findByUserIdAndIsActiveTrue를 써서,
    // 목록에는 없는데 userId를 직접 넣으면 조회·변경이 되는 불일치가 없게 한다.
    private User findUser(Long userId) {
        return userRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
