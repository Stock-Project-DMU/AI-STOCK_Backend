package com.teamfp.aistock.domain.admin.service;

import java.util.Comparator;
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
        return userRepository.findAll(pageable).map(AdminUserListResponse::from);
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
     * holdings/orders는 계좌별로 조회한 결과를 하나로 합쳐서 보여준다(NAMING.md 8-17 참고).
     * orders는 계좌별로는 orderedAt 내림차순으로 조회되지만, 여러 계좌 결과를 합치면 전체
     * 순서가 깨지므로 합친 뒤 다시 orderedAt 기준으로 정렬한다.
     */
    private AdminUserDetailResponse buildDetail(User user) {
        List<Account> accounts = accountRepository.findAllByUserId(user.getUserId());

        List<AccountInfoResponse> accountResponses = accounts.stream()
                .map(AccountInfoResponse::from)
                .toList();

        List<HoldingResponse> holdings = accounts.stream()
                .flatMap(account -> holdingValuationService.getHoldingValuations(account.getAccountId()).stream())
                .map(HoldingResponse::of)
                .toList();

        List<OrderHistoryResponse> orders = accounts.stream()
                .flatMap(account -> orderRepository.findAllByAccountIdOrderByOrderedAtDesc(account.getAccountId()).stream())
                .map(OrderHistoryResponse::from)
                .sorted(Comparator.comparing(OrderHistoryResponse::orderedAt).reversed())
                .toList();

        return AdminUserDetailResponse.of(user, accountResponses, holdings, orders);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
