package com.teamfp.aistock.domain.admin.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserListResponse;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.UserRepository;
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
    private final OrderRepository orderRepository;
    private final HoldingValuationService holdingValuationService;

    @Transactional(readOnly = true)
    public Page<AdminUserListResponse> getUsers(Pageable pageable) {
        // 탈퇴(deactivate)한 유저는 관리자 목록에서 제외한다. deactivate()가 loginId/name/email을
        // "deleted_N"/"탈퇴회원"/null로 익명화해버려 관리 대상으로서 의미가 없기 때문이다.
        return userRepository.findAllByIsActiveTrue(pageable).map(AdminUserListResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUserDetail(Long userId) {
        return buildDetail(findUser(userId));
    }

    @Transactional
    public AdminUserDetailResponse updateUserStatus(Long userId, AdminUserStatusRequest request) {
        User user = findUser(userId);
        if (request.status() == UserStatus.SUSPENDED) {
            user.suspend();
        } else {
            user.activate();
        }
        return buildDetail(user);
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
