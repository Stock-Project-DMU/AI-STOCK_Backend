package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 09:00(KST) 지급일이 된 배당 권리를 예수금으로 지급한다(feature/dividend). 지급일은 공시된
 * 지급일, 없으면 배당 기준일 + 45일이다. 권리마다 DividendEntitlementService.payEntitlement()를 별도
 * 트랜잭션으로 호출해, 한 계좌가 실패해도 나머지 지급은 계속된다.
 *
 * "지급일 == 오늘"이 아니라 "지급일 <= 오늘"인 PENDING 권리를 모두 지급한다 — 서버가 09:00에 꺼져
 * 있었던 날의 지급분도 다음 실행에서 함께 나간다. 지급하면 PAID가 되므로 여러 번 돌아도 중복 지급되지
 * 않으며, 같은 이유로 09:00 이후 서버가 뜰 때도 한 번 실행해 놓친 지급을 바로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendPaymentJob {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime PAYMENT_TIME = LocalTime.of(9, 0);

    private final DividendEntitlementService dividendEntitlementService;

    @Async("batchTaskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void payMissedDividends() {
        if (!LocalTime.now(KST).isBefore(PAYMENT_TIME)) {
            payDueDividends();
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void payDueDividends() {
        List<Long> dueEntitlementIds = dividendEntitlementService.getDueEntitlementIds(LocalDate.now(KST));
        if (dueEntitlementIds.isEmpty()) {
            return;
        }
        int failedCount = 0;
        for (Long dividendEntitlementId : dueEntitlementIds) {
            try {
                dividendEntitlementService.payEntitlement(dividendEntitlementId);
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.ACCOUNT_NOT_FOUND) {
                    log.warn("[DividendPaymentJob] 계좌가 없어 배당 지급 건너뜀(SKIPPED) - dividendEntitlementId: {}",
                            dividendEntitlementId);
                    skipQuietly(dividendEntitlementId);
                } else {
                    failedCount++;
                    log.error("[DividendPaymentJob] 배당 지급 실패 - dividendEntitlementId: {}", dividendEntitlementId, e);
                }
            } catch (RuntimeException e) {
                failedCount++;
                log.error("[DividendPaymentJob] 배당 지급 실패 - dividendEntitlementId: {}", dividendEntitlementId, e);
            }
        }
        log.info("[DividendPaymentJob] 배당 지급 처리 완료 - 대상 {}건, 실패 {}건", dueEntitlementIds.size(), failedCount);
    }

    // SKIPPED 처리마저 실패해도 남은 권리 지급은 계속한다(권리는 PENDING으로 남아 다음 실행에서 다시 시도된다).
    private void skipQuietly(Long dividendEntitlementId) {
        try {
            dividendEntitlementService.skipEntitlement(dividendEntitlementId);
        } catch (RuntimeException e) {
            log.error("[DividendPaymentJob] 배당 권리 SKIPPED 처리 실패 - dividendEntitlementId: {}", dividendEntitlementId, e);
        }
    }
}
