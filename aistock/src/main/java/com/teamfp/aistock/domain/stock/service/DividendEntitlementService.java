package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlement;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlementStatus;
import com.teamfp.aistock.domain.stock.entity.DividendSchedule;
import com.teamfp.aistock.domain.stock.repository.DividendEntitlementRepository;
import com.teamfp.aistock.domain.stock.repository.DividendScheduleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배당 권리 부여·지급(feature/dividend). DividendEntitlementJob/DividendPaymentJob이 회차 1건·권리 1건
 * 단위로 호출해, 메서드마다 별도 트랜잭션으로 처리된다 — 한 건이 실패해도 나머지는 계속 진행된다
 * (AccountInterestJob → AccountService.payMonthlyInterest()와 같은 구조).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendEntitlementService {

    private final DividendScheduleRepository dividendScheduleRepository;
    private final DividendEntitlementRepository dividendEntitlementRepository;
    private final HoldingValuationService holdingValuationService;
    private final AccountService accountService;
    private final DividendScheduleService dividendScheduleService;

    /**
     * 회차를 지금 1주 이상 보유한 모든 계좌에 배당 권리(PENDING)를 만든다. 이미 권리가 있는 계좌는
     * 건너뛰어 잡이 같은 날 다시 돌아도 중복 생성되지 않는다. 새로 만든 권리 수를 반환한다.
     */
    @Transactional
    public int grantEntitlements(Long dividendScheduleId) {
        DividendSchedule schedule = dividendScheduleRepository.findById(dividendScheduleId).orElse(null);
        if (schedule == null || schedule.getDpsCash() == null || schedule.getExDividendDate() == null) {
            return 0;
        }
        Set<Long> entitledAccountIds = dividendEntitlementRepository.findAccountIdsByDividendScheduleId(dividendScheduleId);
        int createdCount = 0;
        for (Map.Entry<Long, Integer> holding : holdingValuationService.getHoldingQuantitiesByStockCode(schedule.getStockCode()).entrySet()) {
            if (entitledAccountIds.contains(holding.getKey())) {
                continue;
            }
            dividendEntitlementRepository.save(DividendEntitlement.builder()
                    .accountId(holding.getKey())
                    .dividendSchedule(schedule)
                    .quantity(holding.getValue())
                    .build());
            createdCount++;
        }
        return createdCount;
    }

    /** 지급일(공시 지급일, 없으면 기준일 + 45일)이 오늘 이전인 PENDING 권리 ID. */
    @Transactional(readOnly = true)
    public List<Long> getDueEntitlementIds(LocalDate today) {
        return dividendEntitlementRepository.findAllWithScheduleByStatus(DividendEntitlementStatus.PENDING).stream()
                .filter(entitlement -> {
                    LocalDate payDate = entitlement.resolvePayDate();
                    return payDate != null && !payDate.isAfter(today);
                })
                .map(DividendEntitlement::getDividendEntitlementId)
                .toList();
    }

    /**
     * 권리 1건을 지급한다: 예수금 입금 + 원장(DIVIDEND) 기록 + 권리 PAID. 한 트랜잭션이라 어느 단계든
     * 실패하면 모두 롤백된다. 이미 지급(또는 건너뜀) 처리된 권리면 아무 것도 하지 않는다. 계좌가 없으면
     * AccountService가 ACCOUNT_NOT_FOUND를 던진다(호출부가 skipEntitlement로 닫음).
     */
    @Transactional
    public void payEntitlement(Long dividendEntitlementId) {
        // 권리 행을 먼저 잠근다 — 잠금 없이 PENDING을 확인하면 겹쳐 돈 두 지급이 모두 통과해 두 번 입금된다.
        DividendEntitlement entitlement = dividendEntitlementRepository.findByIdForUpdate(dividendEntitlementId).orElse(null);
        if (entitlement == null || !entitlement.isPending()) {
            return;
        }
        DividendSchedule schedule = entitlement.getDividendSchedule();
        String stockName = dividendScheduleService.resolveStockName(entitlement.getStockCode());
        String reason = String.format("%s 배당금 입금 (%s %s, %d주 × %,d원)", stockName, schedule.getFiscalYear(),
                schedule.getPeriod(), entitlement.getQuantity(), entitlement.getDpsCash());
        accountService.payDividend(entitlement.getAccountId(), entitlement.getTotalAmount(), reason);
        entitlement.markPaid(LocalDateTime.now());
        log.info("배당금 지급 완료 - accountId={}, stockCode={}, amount={}원",
                entitlement.getAccountId(), entitlement.getStockCode(), entitlement.getTotalAmount());
    }

    /** 지급할 계좌가 없어진(탈퇴) 권리를 SKIPPED로 닫는다. */
    @Transactional
    public void skipEntitlement(Long dividendEntitlementId) {
        dividendEntitlementRepository.findByIdForUpdate(dividendEntitlementId)
                .filter(DividendEntitlement::isPending)
                .ifPresent(DividendEntitlement::markSkipped);
    }
}
