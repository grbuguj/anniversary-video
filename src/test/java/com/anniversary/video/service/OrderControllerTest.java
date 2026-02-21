package com.anniversary.video.service;

import com.anniversary.video.controller.OrderController;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.service.S3Service;
import com.anniversary.video.service.VideoGenerationService;
import com.anniversary.video.dto.OrderCreateRequest;
import com.anniversary.video.dto.OrderCreateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  OrderService orderService;
    @MockBean  S3Service s3Service;
    @MockBean  OrderPhotoRepository orderPhotoRepository;
    @MockBean  VideoGenerationService videoGenerationService;

    @Test
    @DisplayName("POST /api/orders - 정상 주문 200 응답")
    @WithMockUser
    void createOrder_returns200() throws Exception {
        OrderCreateResponse mockResp = OrderCreateResponse.builder()
                .orderId(1L).amount(29900).presignedUrls(List.of()).build();
        given(orderService.createOrder(any())).willReturn(mockResp);

        OrderCreateRequest req = new OrderCreateRequest();
        req.setCustomerName("김철수");
        req.setCustomerPhone("01011112222");
        req.setPhotoCount(12);

        mockMvc.perform(post("/api/orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.amount").value(29900));
    }

    @Test
    @DisplayName("POST /api/orders - 연락처 검증 실패 시 400")
    @WithMockUser
    void createOrder_invalidPhone_returns400() throws Exception {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setCustomerName("김철수");
        req.setCustomerPhone("0101234");
        req.setPhotoCount(12);

        mockMvc.perform(post("/api/orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail.customerPhone").exists());
    }

    @Test
    @DisplayName("POST /api/orders - 사진 수 범위 초과 시 400")
    @WithMockUser
    void createOrder_tooManyPhotos_returns400() throws Exception {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setCustomerName("김철수");
        req.setCustomerPhone("01011112222");
        req.setPhotoCount(20);

        mockMvc.perform(post("/api/orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail.photoCount").exists());
    }

    @Test
    @DisplayName("GET /api/orders/payment-config - clientKey 반환")
    @WithMockUser
    void getPaymentConfig_returns200() throws Exception {
        mockMvc.perform(get("/api/orders/payment-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientKey").exists());
    }
}
