package com.teamfp.aistock.global.redis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.teamfp.aistock.domain.order.dto.PendingOrderDto;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지정가(LIMIT) 미체결 주문 대기 목록을 Redis 리스트로 관리하는 서비스.
 *
 * 종목코드별로 pending:orders:{stockCode} 리스트에 미체결 주문을 쌓아두고,
 * LS증권에서 해당 종목의 체결 tick이 들어올 때마다 이 리스트를 훑어서
 * "지정가 조건(매수는 현재가 이하, 매도는 현재가 이상)을 만족하는 주문이 있는지" 확인하는 데 쓴다.
 * DB 대신 Redis를 쓰는 이유는 매 tick(초당 여러 번)마다 DB를 조회하면 부하가 크기 때문이다.
 *
 * <p><b>서버 시작 순서가 매우 중요하다.</b> 순서가 어긋나면 서버 재시작 사이에 들어온
 * 지정가 주문이 tick 매칭 대상에서 누락되어 영원히 체결되지 않는 사고로 이어질 수 있다.</p>
 * <pre>
 * 1. 서버 시작
 * 2. DB에서 status = PENDING인 주문 전체 조회
 * 3. Redis pending:orders:{stockCode} 리스트에 전부 재적재 완료
 * 4. (완료된 다음에만) LS증권 WebSocket 연결 시작
 * </pre>
 *
 * <p>체결/취소 시점에는 DB 갱신과 Redis 리스트 제거를 같은 처리 흐름 안에서 함께 수행하므로
 * (OrderExecutionService 쪽에서 execute/cancel 후 removePendingOrder 호출),
 * 별도의 배치 동기화 작업 없이도 DB와 Redis 상태가 항상 일치하게 유지된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPendingOrderService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;

    private static final String PENDING_KEY = "pending:orders:";

    /**
     * 서버 기동 시 DB의 PENDING 주문을 Redis로 재적재한다.
     * {@code @PostConstruct}로 빈 생성 직후 자동 실행되며, 이 메서드가 끝난 뒤에
     * LS증권 WebSocket 연결이 시작되도록 빈 초기화 순서(또는 별도 기동 로직)에서 보장해야 한다.
     *
     * 처리 순서:
     * 1) 기존에 Redis에 남아있을 수 있는 pending:orders:* 키를 전부 지운다.
     *    - 여기서 {@code KEYS pending:orders:*} 명령을 쓰면 Redis 전체를 블로킹시키는
     *      안티패턴이 되므로, 커서 기반 {@code SCAN} 명령으로 안전하게 순회한다.
     * 2) DB에서 status = PENDING인 주문을 전부 조회해서 Redis 리스트로 다시 쌓는다.
     *    이렇게 "전체 초기화 후 재적재"를 매번 하기 때문에, 서버가 죽었다 살아나도
     *    DB와 Redis 상태가 항상 다시 일치하게 된다.
     */
    @PostConstruct
    public void initPendingOrders() {
        // 1) 기존 pending:orders:* 키 전체 삭제 (SCAN 커서 사용 — KEYS 블로킹 회피)
        Set<String> keys = new HashSet<>();
        try (Cursor<byte[]> cursor = redisTemplate.execute((RedisConnection connection) ->
                connection.scan(ScanOptions.scanOptions().match(PENDING_KEY + "*").count(100).build()))) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next()));
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // 2) DB의 PENDING 주문을 전부 조회해서 종목코드별 리스트로 재적재
        // account, account.user를 fetch join으로 함께 로딩해야 한다.
        // findAllByStatus()는 이 리포지토리 메서드 호출 동안만 트랜잭션이 열려있어서,
        // 메서드가 리턴된 뒤 아래 for문에서 LAZY 프록시(order.getAccount().getUser())를
        // 건드리면 세션이 이미 닫힌 상태라 LazyInitializationException이 발생한다.
        List<Order> pendingOrders = orderRepository.findAllByStatusWithAccountAndUser(OrderStatus.PENDING);
        for (Order order : pendingOrders) {
            try {
                PendingOrderDto dto = PendingOrderDto.builder()
                    .orderId(order.getOrderId())
                    .userId(order.getAccount().getUser().getUserId())
                    .accountId(order.getAccount().getAccountId())
                    .orderType(order.getOrderType().name())
                    .limitPrice(order.getOrderPrice())
                    .quantity(order.getQuantity())
                    .stockCode(order.getStockCode())
                    .stockName(order.getStockName())
                    .build();
                redisTemplate.opsForList().rightPush(
                    PENDING_KEY + order.getStockCode(),
                    objectMapper.writeValueAsString(dto)
                );
            } catch (JacksonException e) {
                // 재적재는 서버 기동 흐름 전체를 막으면 안 되므로, 개별 주문 실패는
                // 예외를 던지지 않고 로그만 남긴 뒤 나머지 주문 재적재를 계속 진행한다.
                log.error("PENDING 주문 재적재 실패: orderId={}", order.getOrderId(), e);
            }
        }
        log.info("PENDING 주문 재적재 완료: {}건", pendingOrders.size());
    }

    /**
     * 신규 지정가 주문을 미체결 대기 리스트에 추가한다.
     * 주문 등록 API(OrderService)에서 지정가 주문을 DB에 저장한 직후 함께 호출되어야 한다.
     * TTL을 두지 않는다 — 체결되거나 사용자가 취소하기 전까지는 계속 남아있어야 하기 때문이다.
     *
     * @param stockCode 종목코드
     * @param order     추가할 미체결 주문 정보
     */
    public void addPendingOrder(String stockCode, PendingOrderDto order) {
        try {
            redisTemplate.opsForList().rightPush(
                PENDING_KEY + stockCode,
                objectMapper.writeValueAsString(order)
            );
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    /**
     * 특정 종목의 미체결 주문 전체를 조회한다.
     * LS증권에서 해당 종목의 체결 tick을 수신할 때마다 호출되어, 지정가 체결 조건을
     * 만족하는 주문이 있는지 검사하는 용도로 쓰인다 (초당 여러 번 호출될 수 있는 경로).
     *
     * @param stockCode 종목코드
     * @return 미체결 주문 목록, 없으면 빈 리스트 (null 아님)
     */
    public List<PendingOrderDto> getPendingOrders(String stockCode) {
        List<String> jsonList = redisTemplate.opsForList().range(PENDING_KEY + stockCode, 0, -1);
        List<PendingOrderDto> result = new ArrayList<>();
        if (jsonList == null) {
            return result;
        }
        try {
            for (String json : jsonList) {
                result.add(objectMapper.readValue(json, PendingOrderDto.class));
            }
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
        return result;
    }

    /**
     * 체결되었거나 사용자가 취소한 주문을 미체결 리스트에서 제거한다.
     * Redis List는 값으로 원소를 찾아 지우는 구조라, orderId가 일치하는 항목의
     * 원본 JSON 문자열을 찾아서 {@code LREM}으로 정확히 그 한 건만 제거한다.
     *
     * @param stockCode 종목코드
     * @param orderId   제거할 주문의 ID
     */
    public void removePendingOrder(String stockCode, Long orderId) {
        List<String> jsonList = redisTemplate.opsForList().range(PENDING_KEY + stockCode, 0, -1);
        if (jsonList == null) {
            return;
        }
        try {
            for (String json : jsonList) {
                PendingOrderDto order = objectMapper.readValue(json, PendingOrderDto.class);
                if (order.getOrderId().equals(orderId)) {
                    // count=1: 혹시 동일한 JSON 값이 여러 개 있어도 한 건만 제거
                    redisTemplate.opsForList().remove(PENDING_KEY + stockCode, 1, json);
                    break;
                }
            }
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }
}
