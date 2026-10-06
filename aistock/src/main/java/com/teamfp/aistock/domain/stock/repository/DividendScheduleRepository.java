package com.teamfp.aistock.domain.stock.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.stock.entity.DividendSchedule;

public interface DividendScheduleRepository extends JpaRepository<DividendSchedule, Long> {

    // 배당락일이 오늘인 회차(DividendEntitlementJob)
    List<DividendSchedule> findAllByExDividendDate(LocalDate exDividendDate);

    // 보유 종목 중 배당락일이 아직 오지 않은 회차(GET /api/dividends/upcoming)
    List<DividendSchedule> findAllByStockCodeInAndExDividendDateGreaterThanEqual(Collection<String> stockCodes,
            LocalDate exDividendDate);

    // 배당 스케줄 목록(GET /api/dividends/schedule) — stockCode·fiscalYear는 null이면 조건에서 빠진다.
    @Query("select s from DividendSchedule s"
            + " where (:stockCode is null or s.stockCode = :stockCode)"
            + " and (:fiscalYear is null or s.fiscalYear = :fiscalYear)"
            + " order by s.stockCode asc, s.fiscalYear asc, s.period asc")
    List<DividendSchedule> search(@Param("stockCode") String stockCode, @Param("fiscalYear") String fiscalYear);
}
