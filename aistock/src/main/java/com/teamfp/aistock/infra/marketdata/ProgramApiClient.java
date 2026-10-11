package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.ProgramTradingRankDto;
import com.teamfp.aistock.infra.marketdata.dto.ProgramTradingSnapshotDto;

import lombok.RequiredArgsConstructor;

/**
 * 프로그램 매매 — 종목별 순매수 상위 랭킹과 시장 전체 스냅샷(AI 재무설계사 상담 도구). local-market-data-generator가
 * market_data.json의 {@code _market.programTop}(30초)·{@code _market.programSnapshot}(5초)에 갱신하는 모의 값(백만원)을
 * 읽는다(fix/local-market-data-stable — 이전에는 외부 시세 데이터 t1636/t1640을 호출했다).
 */
@Component
@RequiredArgsConstructor
public class ProgramApiClient {

    private static final int MAX_RANK_ITEMS = 10;

    private final LocalMarketDataReader localMarketDataReader;

    /** 프로그램매매 순매수 상위 종목 랭킹(최대 {@value #MAX_RANK_ITEMS}건). 데이터가 없으면 빈 목록. */
    public List<ProgramTradingRankDto> getTopProgramTradingStocks() {
        List<ProgramTradingRankDto> items = localMarketDataReader.getMarketList("programTop", ProgramTradingRankDto.class);
        return items.size() > MAX_RANK_ITEMS ? items.subList(0, MAX_RANK_ITEMS) : items;
    }

    /** 시장 전체 프로그램매매 매도·매수·순매수 대금 스냅샷. 데이터가 없으면 빈 값. */
    public Optional<ProgramTradingSnapshotDto> getMarketSnapshot() {
        return localMarketDataReader.getMarketObject("programSnapshot", ProgramTradingSnapshotDto.class);
    }
}
