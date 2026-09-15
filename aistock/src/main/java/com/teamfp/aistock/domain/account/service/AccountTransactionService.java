package com.teamfp.aistock.domain.account.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.response.AccountTransactionResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * 잔고 변동 원장 기록·조회(ADMIN_API_BACKEND_HANDOFF.md 4.3). 다른 도메인 서비스(AccountService,
 * OrderService, OrderExecutionService, AdminChargeRequestService, AdminAccountTransactionService)가
 * 잔고를 바꾸는 Account 엔티티 메서드(applyBuyOrder/applySellOrder/freezeForOrder/
 * unfreezeForOrder/settleFrozenOrder/chargeBalance/applyAdminCharge/applyAdminDeduction)를 호출한
 * "직후"에 이 서비스의 record()를 호출해 변동 이력을 남긴다 — 원장은 append-only이므로 이
 * 서비스에는 수정·삭제 메서드가 없다.
 */
@Service
@RequiredArgsConstructor
public class AccountTransactionService {

    private final AccountTransactionRepository accountTransactionRepository;

    /**
     * @param balanceBefore 호출부가 Account 엔티티의 잔고 변경 메서드를 호출하기 "직전"에 미리
     *                       읽어둔 balance 값. account.getBalance()는 이미 변경된 이후 값(=balanceAfter)이라
     *                       변경 전 값은 호출부 책임으로 넘겨받는다.
     */
    @Transactional
    public void record(Account account, AccountTransactionType type, long amount, long balanceBefore,
            Long relatedOrderId, Long relatedChargeRequestId, Long processedBy, String reason) {
        AccountTransaction transaction = AccountTransaction.builder()
                .account(account)
                .type(type)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(account.getBalance())
                .relatedOrderId(relatedOrderId)
                .relatedChargeRequestId(relatedChargeRequestId)
                .processedBy(processedBy)
                .reason(reason)
                .build();
        accountTransactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public Page<AccountTransactionResponse> getTransactions(Long accountId, Pageable pageable) {
        return accountTransactionRepository.findAllByAccount_AccountId(accountId, pageable)
                .map(AccountTransactionResponse::from);
    }
}
