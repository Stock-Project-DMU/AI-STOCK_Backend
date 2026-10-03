package com.teamfp.aistock.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}
