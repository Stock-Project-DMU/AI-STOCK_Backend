package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.stock.entity.DividendSchedule;
import com.teamfp.aistock.domain.stock.repository.DividendScheduleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배당락일 장 시작 전(매일 06:00 KST)에 그날이 배당락일인 회차의 배당 권리를 만든다(feature/dividend).
 * 06:00 시점의 보유 수량은 전 거래일 장 마감 기준 보유 수량이고, 이는 "배당락일 전날까지 산 사람이
 * 배당을 받는다"는 실제 규칙과 같다. 회차마다 DividendEntitlementService.grantEntitlements()를 별도
 * 트랜잭션으로 호출해, 한 회차가 실패해도 나머지는 계속 진행된다.
 *
 * 서버가 뜰 때는 먼저 dividends.json을 dividend_schedules에 적재한다. 06:00에 서버가 꺼져 있었다면
 * 그날 권리 부여를 통째로 놓치므로, 기동 시각이 장 시작(09:00) 전이면 오늘 배당락 회차 부여도 한 번
 * 더 실행한다 — 장이 열린 뒤에는 보유 수량이 이미 바뀌었을 수 있어 보충하지 않는다. 이 기동 작업은
 * AccountInterestJob의 보충 지급과 같은 단일 스레드(batchTaskExecutor)에서 돌아 서버 기동을 늦추지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendEntitlementJob {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

    private final DividendScheduleRepository dividendScheduleRepository;
    private final DividendScheduleService dividendScheduleService;
    private final DividendEntitlementService dividendEntitlementService;

    @Async("batchTaskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void initializeOnStartup() {
        try {
            dividendScheduleService.reloadSchedules();
        } catch (RuntimeException e) {
            log.error("[DividendEntitlementJob] 서버 기동 시 배당 스케줄 적재 실패", e);
        }
        if (LocalTime.now(KST).isBefore(MARKET_OPEN_TIME)) {
            log.info("[DividendEntitlementJob] 장 시작 전 기동이라 오늘 배당락 회차 권리 부여를 보충 실행합니다.");
            grantTodayEntitlements();
        }
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void grantTodayEntitlements() {
        LocalDate today = LocalDate.now(KST);
        for (DividendSchedule schedule : dividendScheduleRepository.findAllByExDividendDate(today)) {
            if (schedule.getDpsCash() == null) {
                log.info("[DividendEntitlementJob] 배당금 미공시로 권리 부여 건너뜀 - {} {} {}",
                        schedule.getStockCode(), schedule.getFiscalYear(), schedule.getPeriod());
                continue;
            }
            try {
                int count = dividendEntitlementService.grantEntitlements(schedule.getDividendScheduleId());
                log.info("배당 권리 부여 완료 - {} {} {}: {}개 계좌",
                        schedule.getStockCode(), schedule.getFiscalYear(), schedule.getPeriod(), count);
            } catch (RuntimeException e) {
                log.error("[DividendEntitlementJob] 배당 권리 부여 실패 - {} {} {}",
                        schedule.getStockCode(), schedule.getFiscalYear(), schedule.getPeriod(), e);
            }
        }
    }
}
