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

    @Min(value = 10, message = "사진은 최소 10장 이상 업로드해야 합니다")
    @Max(value = 15, message = "사진은 최대 15장까지 업로드 가능합니다")
    private int photoCount;
}
