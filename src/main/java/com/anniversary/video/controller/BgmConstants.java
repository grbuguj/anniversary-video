package com.anniversary.video.controller;

import java.util.List;
import java.util.Map;

/**
 * BGM 메타 정보 — 프론트 선택 UI + FFmpeg 참조용.
 * FfmpegService 안에 있던 static 상수를 분리.
 */
public final class BgmConstants {

    private BgmConstants() {}

    public static final List<Map<String, String>> BGM_LIST = List.of(
        Map.of("id", "bgm_01", "name", "잔잔한 피아노",    "desc", "조용하고 감성적인 피아노 멜로디"),
        Map.of("id", "bgm_02", "name", "따뜻한 스트링스", "desc", "현악기가 만드는 감동적인 선율"),
        Map.of("id", "bgm_03", "name", "어쿠스틱 기타",   "desc", "자연스럽고 편안한 어쿠스틱 기타")
    );
}
