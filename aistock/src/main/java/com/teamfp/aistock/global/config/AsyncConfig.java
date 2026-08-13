package com.teamfp.aistock.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
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
}
