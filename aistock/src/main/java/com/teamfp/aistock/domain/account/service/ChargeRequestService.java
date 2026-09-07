package com.teamfp.aistock.domain.account.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.dto.response.ChargeRequestResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 — 추가 충전 요청 생성·조회(ADMIN_API_BACKEND_HANDOFF.md 4.2). 계좌 소유권 검증은
 * AccountService.getOwnedAccount()로 일원화한다(OrderService와 동일한 패턴).
 */
@Service
@RequiredArgsConstructor
public class ChargeRequestService {

    private final ChargeRequestRepository chargeRequestRepository;
    private final AccountService accountService;

    /**
     * 계좌별 미처리 요청 중복 생성 방지(handoff 4.2 — 정책 미확정 상태에서 "동시에 PENDING 1건만
     * 허용"으로 우선 구현). 최종 정책이 다르게 정해지면 이 검사만 조정하면 된다.
     *
     * 계좌를 getOwnedAccountForUpdate()로 비관적 락을 걸어 조회한다(코드리뷰 반영, 2026-09) —
     * 락 없이 existsByAccount_AccountIdAndStatus() 조회만으로 중복을 막으면, 같은 계좌로 거의
     * 동시에 두 요청이 들어왔을 때 둘 다 "PENDING 없음"을 보고 통과해 PENDING 요청이 2건
     * 생길 수 있다(체크와 저장 사이의 TOCTOU 경합). 같은 계좌 행에 락을 걸어 두 번째 요청이
     * 첫 번째 요청의 커밋을 기다렸다가 갱신된 상태를 다시 보게 만든다.
     *
     * 계좌 정지(SUSPENDED) 여부도 검증한다 — OrderService.createMarketOrder()/createLimitOrder()가
     * 정지 계좌의 매수·매도를 막는 것과 동일한 이유로, 정지된 계좌에서 새 충전 요청을 만들고
     * 그게 나중에 승인되어 잔고가 늘어나는 것도 막아야 한다.
     */
    @Transactional
    public ChargeRequestResponse createRequest(Long userId, Long accountId, ChargeRequestCreateRequest request) {
        Account account = accountService.getOwnedAccountForUpdate(userId, accountId);
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        if (chargeRequestRepository.existsByAccount_AccountIdAndStatus(accountId, ChargeRequestStatus.PENDING)) {
            throw new CustomException(ErrorCode.CHARGE_REQUEST_ALREADY_PENDING);
        }

        ChargeRequest chargeRequest = ChargeRequest.builder()
                .account(account)
                .amount(request.amount())
                .reason(request.reason())
                .build();
        chargeRequestRepository.save(chargeRequest);

        return ChargeRequestResponse.from(chargeRequest);
    }

    @Transactional(readOnly = true)
    public Page<ChargeRequestResponse> getMyRequests(Long userId, Long accountId, Pageable pageable) {
        accountService.getOwnedAccount(userId, accountId);
        return chargeRequestRepository.findAllByAccountId(accountId, pageable).map(ChargeRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public ChargeRequestResponse getMyRequestDetail(Long userId, Long accountId, Long requestId) {
        accountService.getOwnedAccount(userId, accountId);
        return chargeRequestRepository.findByRequestIdAndAccountId(requestId, accountId)
                .map(ChargeRequestResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.CHARGE_REQUEST_NOT_FOUND));
    }
}
