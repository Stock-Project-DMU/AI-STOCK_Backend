package com.teamfp.aistock.infra.marketdata;

import com.teamfp.aistock.infra.marketdata.dto.HogaData;
import com.teamfp.aistock.infra.marketdata.dto.TickData;

/**
 * 외부 시세 데이터 제공사 WebSocket으로 수신한 실시간 시세를 domain 계층에 전달하기 위한 콜백 인터페이스.
 *
 * CLAUDE.md 4번("도메인 간 직접 참조 대신 서비스 계층을 통해 호출", "외부 API 호출 코드는
 * infra에만 작성하고 domain 서비스는 infra 클라이언트를 주입받아 사용")에 따라 infra는
 * domain을 직접 참조하지 않는다. StockBroadcastService가 이 인터페이스를 구현해 스프링
 * 빈으로 등록하면, MockMarketDataGenerator가 등록된 구현체 전체를 자동 주입받아 호출한다.
 */
public interface MarketDataListener {

    void onTickReceived(TickData tickData);

    void onHogaReceived(HogaData hogaData);
}
