package com.anniversary.video.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/payment/success")
    public String paymentSuccess() {
        return "forward:/index.html";
    }

    @GetMapping("/payment/fail")
    public String paymentFail() {
        return "forward:/index.html";
    }

    /** 주문 상태 확인 페이지 (/status?orderId=123) */
    @GetMapping("/status")
    public String orderStatus() {
        return "forward:/status.html";
    }
}
