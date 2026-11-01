package com.teamfp.aistock.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

// @EnableScheduling(feature/ai-news 추가) — AiNewsService.generateDailyBriefings()의
// @Scheduled 매일 배치를 활성화한다. 이 프로젝트에서 스케줄링을 쓰는 곳이 여기가 처음이라
// 별도 SchedulingConfig를 새로 만드는 대신, 이미 "실행 관련 설정"을 모아두는 이 클래스에
// 함께 둔다(@EnableAsync와 같은 성격).
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean
    public Executor tickTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("tick-executor-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Executor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-executor-");
        executor.initialize();
        return executor;
    }

    // 서버 기동 시 실행하는 보충 배치(AccountInterestJob.payMissedMonthlyInterest — 이번 달 미지급 예치금
    // 이자)용. 기동 스레드에서 돌면 계좌가 많을수록 서버 준비가 늦어지고 그동안 들어온 주문과 계좌 락이
    // 부딪힐 수 있어 별도 스레드 하나에서 순서대로 처리한다.
    @Bean
    public Executor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("batch-executor-");
        executor.initialize();
        return executor;
    }

    // AiPlanningService.converseWithTools()가 Gemini의 parallel function calling 응답(한
    // 라운드에서 여러 도구를 동시에 요청)을 실제로 동시에 실행할 때 쓴다. 도구 실행은 DART/네이버
    // 호출을 기다리는 블로킹 I/O라, ForkJoinPool.commonPool()(다른 병렬 스트림과 공유)을 쓰면
    // 예상 못한 병목이 생길 수 있어 전용 풀을 둔다. 한 라운드에서 한 사용자가 동시에 요청할 수
    // 있는 도구는 최대 5개(NEWS_SEARCH/FINANCIALS/CAPITAL_CHANGE/OWNERSHIP/DISCLOSURE)지만, 이
    // 풀은 모든 사용자의 요청이 공유하는 전역 빈이므로 "동시 접속 사용자 여러 명 × 최대 5개"까지
    // 감당할 수 있게 여유를 둔다(Gemini 분당3/일일10 요청 한도가 자연스러운 상한 역할도 한다).
    @Bean
    public Executor aiToolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-tool-executor-");
        executor.initialize();
        return executor;
    }

    // AiPlanningService.sendMessage()가 한 턴 전체(Gemini 판단·도구 실행·최종 답변)를 이 풀에서 돌리고 요청
    // 스레드는 45초까지만 기다린다(AI_RESPONSE_TIMEOUT). 이 작업은 안에서 aiToolTaskExecutor의 도구 작업을
    // 기다리므로 같은 풀을 쓰면 서로 자리를 차지해 멈출 수 있어 따로 둔다. 마감 시간을 넘긴 작업은 결과만
    // 버려지고 끝까지 실행되므로, 큐를 두되 넘치면 바로 거절(RejectedExecutionException)되게 한다.
    // ThreadPoolTaskExecutor는 큐가 꽉 차기 전까지 코어 수 이상으로 스레드를 늘리지 않는다 — 코어를 10으로 두었을 때
    // 40명 동시 부하에서 처리량이 초당 약 2.3건에 묶여 p95가 20초까지 늘었다(2026-10-30 부하 테스트). 요청 스레드는
    // 가상 스레드라 원래 이런 상한이 없었으므로, 동시 50건까지는 바로 실행하고 쉬는 스레드는 정리되게 한다.
    @Bean
    public Executor aiPlanningTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("ai-planning-executor-");
        executor.initialize();
        return executor;
    }

    /**
     * 기본 예약 작업 스케줄러(스레드 4개) — 시황 브리핑·정지 해제·배당·이자 등 {@code @Scheduled} 기본값이 쓴다. 이름이 {@code taskScheduler}여야
     * 스케줄러 빈이 여러 개일 때 기본으로 선택된다. 이전에는 WebSocket 스케줄러(코어 수만큼)가 기본으로 쓰였으므로, 시황 브리핑(Gemini 호출)이
     * 오래 걸려도 다른 예약 작업이 기다리지 않게 여러 개를 둔다 — 쉬는 스레드는 CPU를 거의 쓰지 않는다(2026-10-12 코드리뷰).
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler; // 초기화는 컨테이너가 한다(afterPropertiesSet) — 직접 부르면 두 번 초기화된다
    }

    /**
     * 실시간 시세 전용 스케줄러(스레드 1개) — 1초 폴링({@code MockMarketDataGenerator.pollAndBroadcast})과 메인 화면
     * 지수·순위 전송({@code MarketOverviewBroadcastService})이 오래 걸리는 다른 예약 작업(Gemini 호출 등)에 막히지 않게 분리한다.
     * 스레드를 1개만 더 써서 자원 증가는 최소다.
     */
    @Bean
    public ThreadPoolTaskScheduler marketDataScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("market-data-");
        return scheduler; // 초기화는 컨테이너가 한다(afterPropertiesSet) — 직접 부르면 두 번 초기화된다
    }
}
