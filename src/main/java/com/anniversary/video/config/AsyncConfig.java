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
     * 1건 완료된 후 다음 건 시작하는 순차 방식으로 안정적
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
     * RunwayML 무료 concurrent 5개 제한 → 4로 쪽 여유 확보
     * (주문 1건 실행 중 1타임아웃 요청이 가능하도록)
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
}
