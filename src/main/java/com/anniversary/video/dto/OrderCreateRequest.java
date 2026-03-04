package com.anniversary.video.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderCreateRequest {

    @NotBlank(message = "이름은 필수입니다")
    @Size(max = 20, message = "이름은 20자 이내로 입력해주세요")
    private String customerName;

    @NotBlank(message = "연락처는 필수입니다")
    @Pattern(regexp = "^01[016789]\\d{7,8}$", message = "올바른 휴대폰 번호를 입력해주세요 (예: 01012345678)")
    private String customerPhone;

    @Email(message = "올바른 이메일 주소를 입력해주세요")
    private String customerEmail;

    /** 고정 10장 — 프론트에서 항상 10을 전송, 백엔드에서도 10으로 강제 */
    private int photoCount = 10;

    @NotBlank(message = "영상 제목은 필수입니다")
    @Size(max = 20, message = "영상 제목은 20자 이내로 입력해주세요")
    private String introTitle;

    // bgm_01 ~ bgm_05, null이면 기본값 bgm_01 사용
    private String bgmTrack;
}
