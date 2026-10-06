package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.stock.dto.response.DividendEntitlementResponse;
import com.teamfp.aistock.domain.stock.dto.response.DividendScheduleReloadResponse;
import com.teamfp.aistock.domain.stock.dto.response.DividendScheduleResponse;
import com.teamfp.aistock.domain.stock.dto.response.UpcomingDividendResponse;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlement;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlementStatus;
import com.teamfp.aistock.domain.stock.entity.DividendSchedule;
import com.teamfp.aistock.domain.stock.repository.DividendEntitlementRepository;
import com.teamfp.aistock.domain.stock.repository.DividendScheduleRepository;
import com.teamfp.aistock.infra.marketdata.DividendScheduleReader;
import com.teamfp.aistock.infra.marketdata.RegisteredStockReader;
import com.teamfp.aistock.infra.marketdata.dto.DividendScheduleDto;
import com.teamfp.aistock.infra.marketdata.dto.RegisteredStockDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배당 스케줄 적재(dividends.json → dividend_schedules UPSERT)와 사용자 배당 조회 API(feature/dividend).
 * 적재는 서버 기동 시(DividendEntitlementJob)와 관리자 재적재 API에서 호출된다. 권리 부여·지급은
 * DividendEntitlementService가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendScheduleService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DividendScheduleReader dividendScheduleReader;
    private final DividendScheduleRepository dividendScheduleRepository;
    private final DividendEntitlementRepository dividendEntitlementRepository;
    private final AccountService accountService;
    private final HoldingValuationService holdingValuationService;
    private final RegisteredStockReader registeredStockReader;
    private final StockNameResolver stockNameResolver;

    /**
     * dividends.json을 다시 읽어 (stockCode, fiscalYear, period) 기준으로 UPSERT한다. dpsCash가 null인
     * 회차도 그대로 적재한다 — 공시가 나온 뒤 파일을 다시 만들고 이 메서드를 다시 부르면 채워진다.
     * 파일에서 사라진 회차는 지우지 않는다(이미 만들어진 배당 권리가 회차를 참조하고 있을 수 있음).
     */
    @Transactional
    public DividendScheduleReloadResponse reloadSchedules() {
        List<DividendScheduleDto> fileSchedules = dividendScheduleReader.readDividendSchedules();
        Map<String, DividendSchedule> existingSchedules = dividendScheduleRepository.findAll().stream()
                .collect(Collectors.toMap(
                        schedule -> scheduleKey(schedule.getStockCode(), schedule.getFiscalYear(), schedule.getPeriod()),
                        Function.identity()));

        int createdCount = 0;
        int updatedCount = 0;
        for (DividendScheduleDto fileSchedule : fileSchedules) {
            String key = scheduleKey(fileSchedule.stockCode(), fileSchedule.fiscalYear(), fileSchedule.period());
            DividendSchedule existing = existingSchedules.get(key);
            if (existing == null) {
                DividendSchedule created = dividendScheduleRepository.save(toEntity(fileSchedule));
                existingSchedules.put(key, created); // 파일 안에 같은 회차가 두 번 있어도 중복 INSERT하지 않도록
                createdCount++;
            } else if (existing.refresh(fileSchedule.dividendKind(), fileSchedule.dpsCash(), fileSchedule.recordDate(),
                    fileSchedule.exDividendDate(), fileSchedule.payDate(), fileSchedule.dividendYield(), fileSchedule.source())) {
                updatedCount++;
            }
        }
        log.info("배당 스케줄 {}건 적재 완료 (신규 {}건 / 업데이트 {}건)", fileSchedules.size(), createdCount, updatedCount);
        return new DividendScheduleReloadResponse(fileSchedules.size(), createdCount, updatedCount);
    }

    /** 배당 스케줄 목록. stockCode·year(회계연도)는 선택 조건이다. */
    @Transactional(readOnly = true)
    public List<DividendScheduleResponse> getSchedules(String stockCode, String year) {
        Map<String, String> stockNames = registeredStockNames();
        return dividendScheduleRepository.search(blankToNull(stockCode), blankToNull(year)).stream()
                .map(schedule -> DividendScheduleResponse.of(schedule, resolveStockName(schedule.getStockCode(), stockNames)))
                .toList();
    }

    /** 내 배당 수령 내역(지급 완료분, 지급 시각 최신순). */
    @Transactional(readOnly = true)
    public List<DividendEntitlementResponse> getMyDividends(Long userId) {
        List<Long> accountIds = getMyAccountIds(userId);
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> stockNames = registeredStockNames();
        return dividendEntitlementRepository
                .findAllWithScheduleByAccountIdInAndStatus(accountIds, DividendEntitlementStatus.PAID).stream()
                .map(entitlement -> DividendEntitlementResponse.of(entitlement,
                        resolveStockName(entitlement.getStockCode(), stockNames)))
                .toList();
    }

    /**
     * 내 예정 배당. 권리가 확정돼 지급을 기다리는 회차(PENDING)와, 지금 보유 중인 종목 중 배당락일이
     * 오늘 이후인 회차(아직 권리 없음 — 현재 보유 수량 기준 예상치)를 배당락일 순으로 합쳐 돌려준다.
     */
    @Transactional(readOnly = true)
    public List<UpcomingDividendResponse> getUpcomingDividends(Long userId) {
        List<Long> accountIds = getMyAccountIds(userId);
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> stockNames = registeredStockNames();
        List<UpcomingDividendResponse> upcomingDividends = new ArrayList<>();

        List<DividendEntitlement> pendingEntitlements = dividendEntitlementRepository
                .findAllWithScheduleByAccountIdInAndStatus(accountIds, DividendEntitlementStatus.PENDING);
        for (DividendEntitlement entitlement : pendingEntitlements) {
            upcomingDividends.add(UpcomingDividendResponse.ofEntitlement(entitlement,
                    resolveStockName(entitlement.getStockCode(), stockNames)));
        }

        Map<String, Integer> holdingQuantities = holdingValuationService.getHoldingQuantities(accountIds);
        if (!holdingQuantities.isEmpty()) {
            Set<Long> entitledScheduleIds = pendingEntitlements.stream()
                    .map(entitlement -> entitlement.getDividendSchedule().getDividendScheduleId())
                    .collect(Collectors.toSet());
            List<DividendSchedule> futureSchedules = dividendScheduleRepository
                    .findAllByStockCodeInAndExDividendDateGreaterThanEqual(holdingQuantities.keySet(), LocalDate.now(KST));
            for (DividendSchedule schedule : futureSchedules) {
                if (!entitledScheduleIds.contains(schedule.getDividendScheduleId())) {
                    upcomingDividends.add(UpcomingDividendResponse.ofSchedule(schedule,
                            resolveStockName(schedule.getStockCode(), stockNames),
                            holdingQuantities.get(schedule.getStockCode())));
                }
            }
        }

        upcomingDividends.sort(Comparator.comparing(UpcomingDividendResponse::exDividendDate)
                .thenComparing(UpcomingDividendResponse::stockCode));
        return upcomingDividends;
    }

    /**
     * 배당 입금 원장 문구 등에 쓰는 종목명. 등록 종목 목록(stocks.json) → DB에 저장된 종목명
     * (StockNameResolver) 순으로 찾고, 둘 다 없으면 종목코드를 그대로 쓴다.
     */
    public String resolveStockName(String stockCode) {
        return resolveStockName(stockCode, registeredStockNames());
    }

    private String resolveStockName(String stockCode, Map<String, String> registeredStockNames) {
        String registeredName = registeredStockNames.get(stockCode);
        if (registeredName != null && !registeredName.isBlank()) {
            return registeredName;
        }
        String storedName = stockNameResolver.resolveStockName(stockCode);
        return storedName != null ? storedName : stockCode;
    }

    private Map<String, String> registeredStockNames() {
        Map<String, String> stockNames = new HashMap<>();
        for (RegisteredStockDto stock : registeredStockReader.getRegisteredStocks()) {
            stockNames.put(stock.stockCode(), stock.stockName());
        }
        return stockNames;
    }

    private List<Long> getMyAccountIds(Long userId) {
        return accountService.getMyAccounts(userId).stream()
                .map(AccountInfoResponse::accountId)
                .toList();
    }

    private static DividendSchedule toEntity(DividendScheduleDto fileSchedule) {
        return DividendSchedule.builder()
                .stockCode(fileSchedule.stockCode())
                .fiscalYear(fileSchedule.fiscalYear())
                .period(fileSchedule.period())
                .dividendKind(fileSchedule.dividendKind())
                .dpsCash(fileSchedule.dpsCash())
                .recordDate(fileSchedule.recordDate())
                .exDividendDate(fileSchedule.exDividendDate())
                .payDate(fileSchedule.payDate())
                .dividendYield(fileSchedule.dividendYield())
                .source(fileSchedule.source())
                .build();
    }

    private static String scheduleKey(String stockCode, String fiscalYear, String period) {
        return stockCode + ":" + fiscalYear + ":" + period;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
