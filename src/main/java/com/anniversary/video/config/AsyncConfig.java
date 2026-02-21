package com.anniversary.video.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * 주문 단위 영상 생성 — 동시 3건 처리
     * startVideoGeneration() 가 이 풀에서 실행됨
     */
    @Bean(name = "videoTaskExecutor")
    public Executor videoTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("VideoGen-");
        executor.initialize();
        return executor;
    }

    /**
     * 클립 병렬 생성 전용 풀
     * 사진 1장 → RunwayML 클립 작업을 동시에 최대 5개 처리
     * RunwayML Rate Limit 고려해 5로 제한 (필요 시 조정)
     */
    @Bean(name = "clipTaskExecutor")
    public Executor clipTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ClipGen-");
        executor.initialize();
        return executor;
    }
}
