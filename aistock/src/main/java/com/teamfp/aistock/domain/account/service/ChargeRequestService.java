package com.teamfp.aistock.domain.account.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.dto.response.ChargeHistoryResponse;
import com.teamfp.aistock.domain.account.dto.response.ChargeRequestResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
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
    private final NotificationService notificationService;
    // 충전 이력 조회용 — 직접 충전(AUTO_CHARGE)·관리자 충전(ADMIN_CHARGE) 원장.
    private final AccountTransactionService accountTransactionService;

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
            throw new CustomException(ErrorCode.ACCOUNT_SUSPENDED_CHARGE);
        }
        // 직접 충전 횟수가 남아 있으면 관리자 요청 없이 POST .../charge로 바로 충전해야 한다.
        if (account.hasRemainingChargeCount()) {
            throw new CustomException(ErrorCode.CHARGE_REQUEST_NOT_ALLOWED);
        }
        // 승인돼도 예치금 한도(1조원)를 넘길 요청은 미리 막는다(승인 시점에도 다시 확인한다).
        if (!account.canDeposit(request.amount())) {
            throw new CustomException(ErrorCode.DEPOSIT_LIMIT_EXCEEDED);
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
        notificationService.notify(userId, NotificationType.ACCOUNT, "충전 요청 접수",
                String.format("%,d원 충전 요청이 접수되었습니다. 관리자의 결정을 기다려 주세요.", request.amount()));

        return ChargeRequestResponse.from(chargeRequest);
    }

    @Transactional(readOnly = true)
    public Page<ChargeRequestResponse> getMyRequests(Long userId, Long accountId, Pageable pageable) {
        accountService.getOwnedAccount(userId, accountId);
        return chargeRequestRepository.findAllByAccountId(accountId, pageable).map(ChargeRequestResponse::from);
    }

    /**
     * 충전 이력 — 직접 충전, 관리자 충전 요청(대기·승인·거절), 관리자 수동 지급을 한 목록으로 합쳐 최신순으로
     * 돌려준다. 충전 요청 목록(getMyRequests)만으로는 관리자 승인 없이 바로 반영되는 직접 충전이 이력에
     * 나오지 않아, 화면이 "셀프/관리자"를 함께 보여줄 수 있도록 별도 조회를 둔다.
     *
     * 승인된 요청은 승인 시점 ADMIN_CHARGE 원장(relatedChargeRequestId로 연결)과 같은 건이라, 그 원장은
     * 요청 항목에 잔고만 붙이고 따로 나열하지 않는다. 요청과 연결되지 않은 ADMIN_CHARGE는 관리자 수동 지급이다.
     */
    @Transactional(readOnly = true)
    public List<ChargeHistoryResponse> getChargeHistory(Long userId, Long accountId) {
        accountService.getOwnedAccount(userId, accountId);

        List<AccountTransaction> chargeTransactions = accountTransactionService.getTransactionsByTypes(accountId,
                List.of(AccountTransactionType.AUTO_CHARGE, AccountTransactionType.ADMIN_CHARGE));
        Map<Long, AccountTransaction> approvedByRequestId = chargeTransactions.stream()
                .filter(transaction -> transaction.getRelatedChargeRequestId() != null)
                .collect(Collectors.toMap(AccountTransaction::getRelatedChargeRequestId, Function.identity(),
                        (first, second) -> first));

        List<ChargeHistoryResponse> history = new ArrayList<>();
        for (AccountTransaction transaction : chargeTransactions) {
            if (transaction.getType() == AccountTransactionType.AUTO_CHARGE) {
                history.add(ChargeHistoryResponse.fromSelfCharge(transaction));
            } else if (transaction.getRelatedChargeRequestId() == null) {
                history.add(ChargeHistoryResponse.fromAdminGrant(transaction));
            }
        }
        for (ChargeRequest request : chargeRequestRepository.findAllByAccount_AccountId(accountId)) {
            history.add(ChargeHistoryResponse.fromRequest(request, approvedByRequestId.get(request.getRequestId())));
        }
        history.sort(Comparator.comparing(ChargeHistoryResponse::requestedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return history;
    }

    @Transactional(readOnly = true)
    public ChargeRequestResponse getMyRequestDetail(Long userId, Long accountId, Long requestId) {
        accountService.getOwnedAccount(userId, accountId);
        return chargeRequestRepository.findByRequestIdAndAccountId(requestId, accountId)
                .map(ChargeRequestResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.CHARGE_REQUEST_NOT_FOUND));
    }
}
