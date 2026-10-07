package com.teamfp.aistock.infra.marketdata;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamfp.aistock.infra.marketdata.dto.ThemeConstituentDto;
import com.teamfp.aistock.infra.marketdata.dto.ThemeDto;

import lombok.RequiredArgsConstructor;

/**
 * 테마 — 테마 구성 종목, 종목별 테마, 핫테마(AI 재무설계사 상담 도구). local-market-data-generator가 market_data.json의
 * {@code _market.themeConstituents}·{@code _market.hotThemes}(30초 갱신)와 종목 객체의 {@code themes}에 담는 모의 값을
 * 읽는다(fix/local-market-data-stable — 이전에는 외부 시세 데이터 t8425/t1532/t1533/t1537을 호출했다).
 */
@Component
@RequiredArgsConstructor
public class SectorApiClient {

    private static final int MAX_CONSTITUENT_ITEMS = 10;
    private static final int MAX_HOT_THEME_ITEMS = 5;
    private static final int MAX_STOCK_THEME_ITEMS = 5;

    private final LocalMarketDataReader localMarketDataReader;

    /**
     * 테마명으로 구성종목+시세를 조회한다. 사용자가 말한 이름이 정확하지 않아도 되도록 테마 목록에서 부분일치(공백 무시)로
     * 찾는다 — 정확히 같은 이름을 먼저, 없으면 서로 포함하는 이름을 쓴다. 일치하는 테마가 없으면 빈 목록.
     */
    public List<ThemeConstituentDto> getThemeConstituentsByName(String themeName) {
        if (themeName == null || themeName.isBlank()) {
            return List.of();
        }
        String wanted = normalize(themeName);
        JsonNode themes = localMarketDataReader.getMarketField("themeConstituents");
        JsonNode matched = null;
        for (Iterator<Map.Entry<String, JsonNode>> it = themes.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = normalize(entry.getKey());
            if (name.equals(wanted)) {
                matched = entry.getValue();
                break;
            }
            if (matched == null && (name.contains(wanted) || wanted.contains(name))) {
                matched = entry.getValue();
            }
        }
        List<ThemeConstituentDto> items = localMarketDataReader.convertList(matched, ThemeConstituentDto.class);
        return items.size() > MAX_CONSTITUENT_ITEMS ? items.subList(0, MAX_CONSTITUENT_ITEMS) : items;
    }

    /** 특정 종목이 어떤 테마들에 속하는지(최대 {@value #MAX_STOCK_THEME_ITEMS}개). */
    public List<ThemeDto> getThemesForStock(String stockCode) {
        List<ThemeDto> items = localMarketDataReader.getStockList(stockCode, "themes", ThemeDto.class);
        return items.size() > MAX_STOCK_THEME_ITEMS ? items.subList(0, MAX_STOCK_THEME_ITEMS) : items;
    }

    /** 오늘 상승률이 두드러진 핫테마(최대 {@value #MAX_HOT_THEME_ITEMS}개). */
    public List<ThemeDto> getHotThemes() {
        List<ThemeDto> items = localMarketDataReader.getMarketList("hotThemes", ThemeDto.class);
        return items.size() > MAX_HOT_THEME_ITEMS ? items.subList(0, MAX_HOT_THEME_ITEMS) : items;
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s·/]", "").toLowerCase(java.util.Locale.ROOT);
    }
}
