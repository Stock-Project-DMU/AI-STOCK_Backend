package com.teamfp.aistock.domain.admin.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeHistorySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSortType;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountTransactionResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeHistoryEntryType;
import com.teamfp.aistock.domain.admin.repository.AdminChargeHistoryRepository.HistoryKey;
import com.teamfp.aistock.domain.admin.repository.AdminChargeHistoryRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 "가상계좌 관리 → 충전·차감 이력"(feat/admin-improvements). 처리가 끝난 충전·차감을 한 목록으로 보여준다 —
 * 잔고가 바뀐 기록(셀프 충전·차감, 요청 승인 충전, 관리자 지급·차감)과 거절된 충전 요청. 대기 중인 요청은
 * "충전 요청" 탭에만 나온다. 충전 요청에서 시작된 건은 요청 내용·처리 결과·입금이 한 줄에 함께 담긴다.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountTransactionService {

    // 충전·차감 이력 탭에 보여주는 잔고 내역 유형 — 주문·이자·수수료·계좌 개설 지급은 제외한다.
    static final List<AccountTransactionType> CHARGE_DEDUCTION_TYPES = List.of(
            AccountTransactionType.AUTO_CHARGE,
            AccountTransactionType.ADMIN_CHARGE,
            AccountTransactionType.ADMIN_DEDUCTION,
            AccountTransactionType.AUTO_DEDUCTION);
    // 유형 필터 값 중 거절된 충전 요청만 보는 값
    static final String TYPE_REJECTED = "REJECTED";
    static final String INVALID_TYPE_MESSAGE =
            "유형(type)은 AUTO_CHARGE, ADMIN_CHARGE, ADMIN_DEDUCTION, AUTO_DEDUCTION, REJECTED 중 하나여야 합니다.";

    private final AdminChargeHistoryRepository adminChargeHistoryRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final ChargeRequestRepository chargeRequestRepository;
    private final UserRepository userRepository;

    /**
     * type이 비어 있으면 전체(잔고 내역 4종 + 거절된 요청), 잔고 내역 유형이면 그 유형만, "REJECTED"면 거절된 요청만.
     * 처리 관리자 아이디와 연결된 충전 요청은 한 페이지에 나온 것만 모아 한 번씩 조회해 채운다.
     */
    @Transactional(readOnly = true)
    public Page<AdminAccountTransactionResponse> getChargeDeductionHistory(String type, AdminSearchConditionDto search,
            AdminSortType sortType, AdminChargeHistorySortColumn sortColumn, Sort.Direction direction, Pageable pageable) {
        List<AccountTransactionType> transactionTypes;
        boolean includeRejected;
        if (type == null || type.isBlank()) {
            transactionTypes = CHARGE_DEDUCTION_TYPES;
            includeRejected = true;
        } else if (TYPE_REJECTED.equalsIgnoreCase(type.trim())) {
            transactionTypes = List.of();
            includeRejected = true;
        } else {
            transactionTypes = List.of(parseTransactionType(type.trim()));
            includeRejected = false;
        }

        long total = adminChargeHistoryRepository.count(transactionTypes, includeRejected, search);
        List<HistoryKey> keys = adminChargeHistoryRepository.findKeys(transactionTypes, includeRejected, search, sortType, sortColumn, direction,
                pageable.getOffset(), pageable.getPageSize());

        List<Long> transactionIds = idsOf(keys, AdminChargeHistoryEntryType.TRANSACTION);
        Map<Long, AccountTransaction> transactions = transactionIds.isEmpty() ? Map.of()
                : accountTransactionRepository.findAllWithUserByTransactionIdIn(transactionIds).stream()
                        .collect(Collectors.toMap(AccountTransaction::getTransactionId, Function.identity()));

        // 승인 입금 기록이 가리키는 충전 요청 + 거절된 요청을 한 번에 조회한다.
        List<Long> requestIds = java.util.stream.Stream.concat(
                        transactions.values().stream().map(AccountTransaction::getRelatedChargeRequestId).filter(Objects::nonNull),
                        idsOf(keys, AdminChargeHistoryEntryType.REJECTED_REQUEST).stream())
                .distinct()
                .toList();
        Map<Long, ChargeRequest> requests = requestIds.isEmpty() ? Map.of()
                : chargeRequestRepository.findAllWithUserByRequestIdIn(requestIds).stream()
                        .collect(Collectors.toMap(ChargeRequest::getRequestId, Function.identity()));

        List<Long> processorIds = transactions.values().stream()
                .map(AccountTransaction::getProcessedBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> processorLoginIds = processorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(processorIds).stream()
                        .filter(user -> user.getLoginId() != null)
                        .collect(Collectors.toMap(User::getUserId, User::getLoginId, (first, second) -> first));

        List<AdminAccountTransactionResponse> content = keys.stream()
                .map(key -> {
                    if (key.entryType() == AdminChargeHistoryEntryType.REJECTED_REQUEST) {
                        ChargeRequest rejected = requests.get(key.id());
                        return rejected == null ? null : AdminAccountTransactionResponse.rejected(rejected);
                    }
                    AccountTransaction transaction = transactions.get(key.id());
                    if (transaction == null) {
                        return null;
                    }
                    return AdminAccountTransactionResponse.of(transaction,
                            transaction.getProcessedBy() == null ? null : processorLoginIds.get(transaction.getProcessedBy()),
                            transaction.getRelatedChargeRequestId() == null ? null
                                    : requests.get(transaction.getRelatedChargeRequestId()));
                })
                .filter(Objects::nonNull)
                .toList();
        return new PageImpl<>(content, pageable, total);
    }

    private AccountTransactionType parseTransactionType(String type) {
        try {
            AccountTransactionType parsed = AccountTransactionType.valueOf(type.toUpperCase());
            if (CHARGE_DEDUCTION_TYPES.contains(parsed)) {
                return parsed;
            }
        } catch (IllegalArgumentException e) {
            // 아래에서 같은 메시지로 거절한다.
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, INVALID_TYPE_MESSAGE);
    }

    private List<Long> idsOf(List<HistoryKey> keys, AdminChargeHistoryEntryType entryType) {
        return keys.stream().filter(key -> key.entryType() == entryType).map(HistoryKey::id).toList();
    }
}
