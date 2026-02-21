package com.anniversary.video.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "UP");
        res.put("timestamp", LocalDateTime.now().toString());

        // DB 연결 확인
        try (Connection conn = dataSource.getConnection()) {
            res.put("db", "UP");
        } catch (Exception e) {
            res.put("db", "DOWN: " + e.getMessage());
            res.put("status", "DEGRADED");
        }
        return ResponseEntity.ok(res);
    }
}
