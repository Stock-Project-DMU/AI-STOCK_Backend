package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.stock.entity.DividendSchedule;
import com.teamfp.aistock.domain.stock.repository.DividendScheduleRepository;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/dividend — DividendEntitlementJob 단위 테스트. 오늘 배당락인 회차만 권리 부여 대상이며,
 * 배당금 미공시(dpsCash null) 회차는 서비스 호출 없이 건너뛰고, 한 회차가 실패해도 나머지는 계속한다.
 */
@ExtendWith(MockitoExtension.class)
class DividendEntitlementJobTest {

    @Mock private DividendScheduleRepository dividendScheduleRepository;
    @Mock private DividendScheduleService dividendScheduleService;
    @Mock private DividendEntitlementService dividendEntitlementService;
    @InjectMocks private DividendEntitlementJob dividendEntitlementJob;

    private static DividendSchedule schedule(long dividendScheduleId, Integer dpsCash) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        DividendSchedule schedule = DividendSchedule.builder()
                .stockCode("005930").fiscalYear(String.valueOf(today.getYear())).period("Q" + dividendScheduleId)
                .dpsCash(dpsCash).recordDate(today.plusDays(1)).exDividendDate(today).source("TEST").build();
        ReflectionTestUtils.setField(schedule, "dividendScheduleId", dividendScheduleId);
        return schedule;
    }

    @Test
    void 오늘_배당락인_회차가_없으면_아무_것도_하지_않고_끝난다() {
        when(dividendScheduleRepository.findAllByExDividendDate(LocalDate.now(ZoneId.of("Asia/Seoul")))).thenReturn(List.of());

        dividendEntitlementJob.grantTodayEntitlements();

        verify(dividendEntitlementService, never()).grantEntitlements(anyLong());
    }

    @Test
    void 배당금_미공시_회차는_건너뛰고_한_회차가_실패해도_다음_회차는_처리한다() {
        when(dividendScheduleRepository.findAllByExDividendDate(LocalDate.now(ZoneId.of("Asia/Seoul"))))
                .thenReturn(List.of(schedule(1L, null), schedule(2L, 361), schedule(3L, 500)));
        when(dividendEntitlementService.grantEntitlements(2L)).thenThrow(new IllegalStateException("보유 조회 실패"));
        when(dividendEntitlementService.grantEntitlements(3L)).thenReturn(4);

        dividendEntitlementJob.grantTodayEntitlements();

        verify(dividendEntitlementService, never()).grantEntitlements(1L);
        verify(dividendEntitlementService).grantEntitlements(2L);
        verify(dividendEntitlementService).grantEntitlements(3L);
    }
}
