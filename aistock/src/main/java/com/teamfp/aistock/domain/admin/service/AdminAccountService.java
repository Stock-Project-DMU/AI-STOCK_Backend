package com.teamfp.aistock.domain.admin.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountDetailResponse;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 계좌 상세 조회 및 거래 정지상태 변경. admin 도메인은 자체 Entity/Repository를 두지
 * 않고 account 도메인의 AccountRepository를 그대로 주입받아 조합한다(CLAUDE.md 4번).
 * accounts.status는 로그인은 그대로 두고 매수·매도 주문만 막는 개념이라(CLAUDE.md 8번),
 * AdminUserService.suspend()의 본인/마지막 관리자 lockout 가드가 필요 없다 — 관리자 계정
 * 자체는 users.status로 별도 관리되므로 계좌 정지로는 관리자 접근이 막히지 않는다.
 *
 * 정지(SUSPENDED) 처리 시 order 도메인의 OrderService.cancelAllPendingOrdersForSuspension()을
 * 함께 호출한다 — Repository 직접 조작 대신 서비스 계층을 거치는 이유는 CLAUDE.md 4번
 * "도메인 간 직접 참조 대신 서비스 계층을 통해 호출" 원칙에 따라, PENDING 주문 취소가 갖는
 * 잔고 동결 해제·Redis pending:orders 정리 같은 order 도메인 고유 로직을 이 서비스에서
 * 중복 구현하지 않기 위함이다(코드리뷰 반영).
 */
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    private final AccountRepository accountRepository;
    private final OrderService orderService;

    @Transactional(readOnly = true)
    public AdminAccountDetailResponse getAccountDetail(Long accountId) {
        return AdminAccountDetailResponse.from(findAccount(accountId));
    }

    @Transactional
    public AdminAccountDetailResponse updateAccountStatus(Long accountId, AdminAccountStatusRequest request) {
        Account account = findAccount(accountId);
        if (request.status() == AccountStatus.SUSPENDED) {
            account.suspend();
            // 정지 시점에 남아있는 PENDING 지정가 주문을 그대로 두면 tick 체결이나 사용자의
            // 자력 취소로 정지 정책이 깨질 수 있어(OrderService.cancelAllPendingOrdersForSuspension
            // Javadoc 참고), 정지와 같은 트랜잭션에서 함께 취소한다.
            orderService.cancelAllPendingOrdersForSuspension(account);
        } else {
            account.activate();
        }
        return AdminAccountDetailResponse.from(account);
    }

    private Account findAccount(Long accountId) {
        return accountRepository.findAccountWithUserById(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
