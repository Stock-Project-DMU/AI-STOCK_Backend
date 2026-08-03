package com.teamfp.aistock.infra.ls;

import com.teamfp.aistock.infra.ls.dto.LsHogaData;
import com.teamfp.aistock.infra.ls.dto.LsTickData;

/**
 * LS증권 WebSocket으로 수신한 실시간 시세를 domain 계층에 전달하기 위한 콜백 인터페이스.
 *
 * CLAUDE.md 4번("도메인 간 직접 참조 대신 서비스 계층을 통해 호출", "외부 API 호출 코드는
 * infra에만 작성하고 domain 서비스는 infra 클라이언트를 주입받아 사용")에 따라 infra는
 * domain을 직접 참조하지 않는다. 4주차 feature/stock-price의 StockBroadcastService가 이
 * 인터페이스를 구현해 스프링 빈으로 등록하면, LsWebSocketHandler가 등록된 구현체 전체를
 * 자동 주입받아 호출한다(현재는 구현체가 없어 빈 목록으로 주입된다).
 */
public interface LsMarketDataListener {

    void onTickReceived(LsTickData tickData);

    void onHogaReceived(LsHogaData hogaData);
}
