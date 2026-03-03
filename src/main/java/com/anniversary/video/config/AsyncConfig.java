package com.anniversary.video.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * 주문 단위 영상 생성 — 동시 1건으로 제한
     * RunwayML 무료 플랜: concurrent 5 + 크레딧 제한 있어 주문 동시 실행 불가
     */
    @Bean(name = "videoTaskExecutor")
    public Executor videoTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("VideoGen-");
        executor.initialize();
        return executor;
    }

    /**
     * 클립 병렬 생성 전용 풀
     * RunwayML 무료 concurrent 5개 제한 → 4로 여유 확보
     */
    @Bean(name = "clipTaskExecutor")
    public Executor clipTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ClipGen-");
        executor.initialize();
        return executor;
    }

    /**
     * 이벤트 로깅 전용 풀
     * 비동기로 order_events 저장. 실패해도 비즈니스 로직에 영향 없음.
     * 가벼운 INSERT 작업이라 코어 2개면 충분.
     */
    @Bean(name = "eventLogExecutor")
    public Executor eventLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("EventLog-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }
}
