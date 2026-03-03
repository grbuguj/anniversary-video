package com.anniversary.video.integration;

import com.anniversary.video.domain.Order;
import com.anniversary.video.dto.OrderCreateRequest;
import com.anniversary.video.repository.OrderRepository;
import com.anniversary.video.service.EventLoggingService;
import com.anniversary.video.service.NotificationService;
import com.anniversary.video.service.S3Service;
import com.anniversary.video.service.VideoGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 전체 주문 플로우 통합 테스트 (H2 인메모리 DB)
 * — 외부 API (S3, RunwayML, 솔라피, 슬랙) 전부 Mock
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderRepository orderRepository;

    // 외부 의존성 전부 Mock
    @MockBean S3Service s3Service;
    @MockBean VideoGenerationService videoGenerationService;
    @MockBean NotificationService notificationService;
    @MockBean EventLoggingService eventLoggingService;

    private static final AtomicLong sharedOrderId = new AtomicLong(0);

    @BeforeEach
    void mockExternalCalls() {
        given(s3Service.generateUploadUrls(anyLong(), anyInt())).willReturn(
                List.of(new S3Service.PresignedUploadInfo(0, "https://s3.test/0", "uploads/1/photo_00.jpg"))
        );
        given(s3Service.generateDownloadUrl(anyString())).willReturn("https://cdn.test/result.mp4");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("1. 주문 생성 — DB에 PENDING 상태로 저장")
    @WithMockUser
    void step1_createOrder() throws Exception {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setCustomerName("통합테스트 고객");
        req.setCustomerPhone("01088889999");
        req.setPhotoCount(12);
        req.setIntroTitle("어머니 환갑");

        String body = mockMvc.perform(post("/api/orders").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isNumber())
                .andExpect(jsonPath("$.amount").value(29900))
                .andExpect(jsonPath("$.presignedUrls").isArray())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = objectMapper.readValue(body, Map.class);
        long orderId = ((Number) resp.get("orderId")).longValue();
        sharedOrderId.set(orderId);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PENDING);
        assertThat(order.getCustomerName()).isEqualTo("통합테스트 고객");
        assertThat(order.getPhotoCount()).isEqualTo(12);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("2. PROCESSING 중 동일번호 주문 → 409 차단")
    @WithMockUser
    void step2_processingOrder_blocked() throws Exception {
        // 기존 주문을 PROCESSING으로 변경
        long orderId = sharedOrderId.get();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.updateStatus(Order.OrderStatus.PROCESSING);
        orderRepository.save(order);

        OrderCreateRequest req = new OrderCreateRequest();
        req.setCustomerName("동일고객");
        req.setCustomerPhone("01088889999");
        req.setPhotoCount(10);
        req.setIntroTitle("어머니 환갑");

        mockMvc.perform(post("/api/orders").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("영상 제작 중인 주문")));

        // 원복
        order.updateStatus(Order.OrderStatus.PENDING);
        orderRepository.save(order);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("3. 주문 상태 조회 — PENDING 확인")
    @WithMockUser
    void step3_getStatus_pending() throws Exception {
        long orderId = sharedOrderId.get();
        mockMvc.perform(get("/api/orders/" + orderId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.orderId").value(orderId));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("4. PENDING 주문 업로드 URL 재발급 → 400 거부")
    @WithMockUser
    void step4_uploadUrl_pending_rejected() throws Exception {
        long orderId = sharedOrderId.get();
        mockMvc.perform(get("/api/orders/" + orderId + "/upload-urls"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("5. 관리자 상태 PAID 수동 변경 → DB 저장 확인")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void step5_adminSetPaid_savedToDb() throws Exception {
        long orderId = sharedOrderId.get();

        mockMvc.perform(put("/admin/orders/" + orderId + "/status").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PAID);
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("6. PAID 주문 업로드 URL 재발급 성공")
    @WithMockUser
    void step6_uploadUrlRefresh_paid() throws Exception {
        long orderId = sharedOrderId.get();
        mockMvc.perform(get("/api/orders/" + orderId + "/upload-urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presignedUrls").isArray());
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("7. 관리자 메모 저장 → DB 저장 확인")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void step7_saveMemo_persistedToDb() throws Exception {
        long orderId = sharedOrderId.get();

        mockMvc.perform(put("/admin/orders/" + orderId + "/memo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"고객 확인 완료\"}"))
                .andExpect(status().isOk());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getAdminMemo()).isEqualTo("고객 확인 완료");
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("8. 완료 처리 → downloadUrl DB 저장 확인")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void step8_markCompleted() throws Exception {
        long orderId = sharedOrderId.get();

        mockMvc.perform(put("/admin/orders/" + orderId + "/status").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"downloadUrl\":\"https://cdn.test/final.mp4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value("https://cdn.test/final.mp4"));

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.COMPLETED);
        assertThat(order.getDownloadUrl()).isEqualTo("https://cdn.test/final.mp4");
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("9. 완료된 주문 다운로드 URL 재발급 성공")
    @WithMockUser
    void step9_refreshDownloadUrl() throws Exception {
        long orderId = sharedOrderId.get();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setS3OutputPath("results/" + orderId + "/final.mp4");
        orderRepository.save(order);

        mockMvc.perform(post("/api/orders/" + orderId + "/download-url").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("10. 대시보드 통계 — completed >= 1, revenue >= 29900")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void step10_dashboard() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.revenue").value(Matchers.greaterThanOrEqualTo(29900)));
    }

    @Test
    @org.junit.jupiter.api.Order(11)
    @DisplayName("11. 관리자 상태 필터 — status=COMPLETED")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void step11_filterByStatus() throws Exception {
        mockMvc.perform(get("/admin/orders?status=COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    @org.junit.jupiter.api.Order(12)
    @DisplayName("12. 존재하지 않는 주문 → 400")
    @WithMockUser
    void step12_notFound() throws Exception {
        mockMvc.perform(get("/api/orders/99999/status"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.junit.jupiter.api.Order(13)
    @DisplayName("13. 관리자 재생성 → retryCount 증가 + 분석 필드 초기화")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void step13_regenerate() throws Exception {
        // FAILED 상태 주문 준비
        long orderId = sharedOrderId.get();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.updateStatus(Order.OrderStatus.FAILED);
        order.setFailureStage("clip_generation");
        order.setRetryCount(0);
        orderRepository.save(order);

        mockMvc.perform(post("/admin/orders/" + orderId + "/regenerate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryCount").value("1"));

        Order updated = orderRepository.findById(orderId).orElseThrow();
        assertThat(updated.getRetryCount()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo(Order.OrderStatus.PAID);
        assertThat(updated.getFailureStage()).isNull();
    }

    @Test
    @org.junit.jupiter.api.Order(14)
    @DisplayName("14. 프론트 비콘 이벤트 수신 — POST /api/events")
    @WithMockUser
    void step14_frontBeaconEvent() throws Exception {
        long orderId = sharedOrderId.get();

        mockMvc.perform(post("/api/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"eventType\":\"page_view\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));
    }

    @Test
    @org.junit.jupiter.api.Order(15)
    @DisplayName("15. 비콘 이벤트 — eventType 누락 시 400")
    @WithMockUser
    void step15_beaconWithoutType_rejected() throws Exception {
        mockMvc.perform(post("/api/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1}"))
                .andExpect(status().isBadRequest());
    }
}
