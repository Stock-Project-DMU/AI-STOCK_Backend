package com.teamfp.aistock.infra.marketdata;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamfp.aistock.infra.marketdata.dto.RegisteredStockDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 백엔드에 함께 배포되는 등록 종목 목록(classpath {@code stocks.json}, 2026-10-02 기준 105개)을 읽는다.
 *
 * <p>real 모드의 순위 TR(t1441/t1444/t1452/t1463)은 시장 전체 상위 10건만 돌려줘서, 홈 주요 종목
 * 무한 스크롤({@code GET /api/market/rankings?all=true})과 시뮬레이션 리밸런싱 후보가 mock 모드(105개)와
 * 달리 10개로 잘렸다. {@link HighItemApiClient}가 이 목록의 종목코드로 t8407 현재가를 받아 직접 정렬해
 * mock/real 모두 같은 105개 범위를 반환하도록 하기 위한 종목 범위 기준이다.</p>
 *
 * <p>local-market-data-generator(별도 프로젝트, git 미포함)의 stocks.json과 종목 구성이 같아야 한다 —
 * 그 파일은 팀원 PC에 없을 수 있어 백엔드가 직접 읽지 않고, 필요한 필드만 복사해 resources에 둔다.
 * 파일은 기동 시 한 번만 읽으며, 없거나 깨져 있어도 서버 기동은 막지 않고 빈 목록을 쓴다.</p>
 */
@Slf4j
@Component
public class RegisteredStockReader {

    static final String REGISTERED_STOCKS_RESOURCE = "stocks.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final List<RegisteredStockDto> registeredStocks;

    public RegisteredStockReader() {
        this(REGISTERED_STOCKS_RESOURCE);
    }

    // 테스트에서 다른 리소스 경로(없는 파일 포함)를 넘겨 fallback을 검증하기 위한 생성자.
    RegisteredStockReader(String resourcePath) {
        this.registeredStocks = load(resourcePath);
    }

    /** 등록 종목 전체(파일 순서 유지, 불변 리스트). 파일을 읽지 못했으면 빈 리스트. */
    public List<RegisteredStockDto> getRegisteredStocks() {
        return registeredStocks;
    }

    private static List<RegisteredStockDto> load(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("등록 종목 목록 파일을 찾지 못함 - classpath: {}", resourcePath);
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode stockNodes = OBJECT_MAPPER.readTree(inputStream).path("stocks");
            List<RegisteredStockDto> stocks = new ArrayList<>();
            for (JsonNode node : stockNodes) {
                String stockCode = node.path("code").asText("");
                if (!stockCode.matches("[0-9]{6}")) {
                    continue;
                }
                JsonNode listingShares = node.path("listingShares");
                stocks.add(new RegisteredStockDto(
                        stockCode,
                        node.path("name").asText(""),
                        node.path("market").asText(""),
                        listingShares.isNumber() ? listingShares.asLong() : null));
            }
            log.info("등록 종목 목록 로드 완료 - {}개", stocks.size());
            return List.copyOf(stocks);
        } catch (IOException e) {
            log.warn("등록 종목 목록 파싱 실패 - classpath: {}, 사유: {}", resourcePath, e.getMessage());
            return List.of();
        }
    }
}
