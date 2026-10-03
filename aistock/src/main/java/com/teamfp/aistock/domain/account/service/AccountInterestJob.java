package com.teamfp.aistock.domain.account.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매월 1일 0시(KST) 예치금 이자를 지급한다. 계좌마다 별도 트랜잭션(AccountService.payMonthlyInterest)으로
 * 처리해, 한 계좌가 실패해도 나머지 계좌 지급은 계속된다. 지급 대상은 이번 달 1일 0시 이전에 개설된
 * 계좌뿐이다 — 지급하는 건 "지난달" 이자라, 이번 달에 만든 계좌는 받을 이자가 없다.
 *
 * 스케줄러는 서버 안에서 돌기 때문에 1일 0시 정각에 서버가 꺼져 있으면(배포·재시작 중) 그 달 지급을
 * 통째로 놓친다. 그래서 서버가 뜰 때 이번 달 정기 지급이 아예 안 된 경우(이번 달 INTEREST 원장이 한
 * 건도 없음)에만 한 번 더 실행한다(코드리뷰 반영). 정기 지급이 이미 됐다면 재시작해도 아무 것도 하지
 * 않아, 1일에 잔고가 0이라 이자가 없던 계좌가 그 뒤 충전하고 재시작 때 이자를 받는 일도 막는다.
 * 서버 기동을 늦추거나 기동 직후 주문과 계좌 락이 부딪히지 않도록 이 보충 지급은 별도 스레드에서 돈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountInterestJob {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final AccountTransactionService accountTransactionService;

    @Async("batchTaskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void payMissedMonthlyInterest() {
        if (accountTransactionService.existsAnyTransactionSince(AccountTransactionType.INTEREST, currentMonthStart())) {
            return;
        }
        log.info("[AccountInterestJob] 이번 달 예치금 이자 정기 지급 기록이 없어 서버 기동 시 보충 지급을 시작합니다.");
        payMonthlyInterest();
    }

    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Seoul")
    public void payMonthlyInterest() {
        LocalDateTime paidSince = currentMonthStart();
        int interestMonth = ZonedDateTime.now(KST).minusMonths(1).getMonthValue();

        int failedCount = 0;
        for (Long accountId : accountRepository.findAllAccountIdsOpenedBefore(paidSince)) {
            try {
                accountService.payMonthlyInterest(accountId, paidSince, interestMonth);
            } catch (RuntimeException e) {
                failedCount++;
                log.error("[AccountInterestJob] 예치금 이자 지급 실패 - accountId: {}", accountId, e);
            }
        }
        log.info("[AccountInterestJob] {}월 예치금 이자 지급 완료 - 실패 {}건", interestMonth, failedCount);
    }

    /**
     * 이번 달 1일 0시(KST)를 JVM 기본 시간대의 LocalDateTime으로 바꾼 값. 원장·계좌의 createdAt은 JPA
     * Auditing이 JVM 기본 시간대로 채우므로 비교 기준도 같은 시간대로 맞춘다(서버 시간대가 UTC여도 정확하도록).
     */
    private LocalDateTime currentMonthStart() {
        ZonedDateTime monthStart = ZonedDateTime.now(KST).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        return monthStart.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
