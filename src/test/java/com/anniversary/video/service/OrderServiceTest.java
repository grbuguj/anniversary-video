package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.dto.OrderCreateRequest;
import com.anniversary.video.dto.OrderCreateResponse;
import com.anniversary.video.repository.OrderPhotoRepository;
import com.anniversary.video.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Mock private OrderRepository orderRepository;
    @Mock private OrderPhotoRepository orderPhotoRepository;
    @Mock private S3Service s3Service;
    @Mock private EventLoggingService eventLoggingService;

    private OrderCreateRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new OrderCreateRequest();
        validRequest.setCustomerName("홍길동");
        validRequest.setCustomerPhone("01012345678");
        validRequest.setPhotoCount(12);
        validRequest.setIntroTitle("어머니 환갑");
    }

    @Test
    @DisplayName("정상 주문 생성 - orderId, amount, presignedUrls 반환")
    void createOrder_success() {
        // given
        Order savedOrder = Order.builder()
                .id(1L).customerName("홍길동").customerPhone("01012345678")
                .amount(29900).status(Order.OrderStatus.PENDING).build();

        given(orderRepository.save(any(Order.class))).willReturn(savedOrder);
        given(s3Service.generateUploadUrls(eq(1L), eq(12))).willReturn(
                List.of(
                        new S3Service.PresignedUploadInfo(0, "https://s3.amazonaws.com/test/0", "uploads/1/photo_00.jpg"),
                        new S3Service.PresignedUploadInfo(1, "https://s3.amazonaws.com/test/1", "uploads/1/photo_01.jpg")
                )
        );

        // when
        OrderCreateResponse resp = orderService.createOrder(validRequest);

        // then
        assertThat(resp.getOrderId()).isEqualTo(1L);
        assertThat(resp.getAmount()).isEqualTo(29900);
        assertThat(resp.getPresignedUrls()).isNotEmpty();
        then(orderRepository).should(times(1)).save(any());
        then(eventLoggingService).should().log(eq(1L), eq("order_created"), any());
    }

    @Test
    @DisplayName("PENDING → PAID 상태 전환 성공")
    void markAsPaid_success() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PENDING).build();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willReturn(order);

        Order result = orderService.markAsPaid(1L, "payKey_abc");

        assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.PAID);
        assertThat(result.getPaymentKey()).isEqualTo("payKey_abc");
    }

    @Test
    @DisplayName("존재하지 않는 orderId 조회 시 예외")
    void findById_notFound() {
        given(orderRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("COMPLETED 상태 완료 처리 + genMinutes 계산")
    void markAsCompleted_success() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PROCESSING).build();
        order.setGenStartedAt(LocalDateTime.now().minusMinutes(5));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willReturn(order);

        Order result = orderService.markAsCompleted(1L, "results/1/final.mp4", "https://cdn.example.com/dl");

        assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.COMPLETED);
        assertThat(result.getDownloadUrl()).isEqualTo("https://cdn.example.com/dl");
        assertThat(result.getGenCompletedAt()).isNotNull();
        assertThat(result.getGenMinutes()).isNotNull();
        assertThat(result.getGenMinutes()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("실패 처리 - 메모 + failureStage 저장")
    void markAsFailed_withStage() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PROCESSING).build();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willReturn(order);

        orderService.markAsFailed(1L, "RunwayML 타임아웃", "clip_generation");

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertThat(order.getAdminMemo()).contains("RunwayML 타임아웃");
        assertThat(order.getFailureStage()).isEqualTo("clip_generation");
    }

    @Test
    @DisplayName("재생성 준비 - retryCount 증가 + 분석 필드 초기화")
    void prepareRegeneration_success() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.FAILED)
                .retryCount(1).failureStage("ffmpeg_merge").build();
        order.setGenStartedAt(LocalDateTime.now().minusMinutes(10));
        order.setGenCompletedAt(LocalDateTime.now());
        order.setGenMinutes(BigDecimal.TEN);

        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willReturn(order);

        Order result = orderService.prepareRegeneration(1L);

        assertThat(result.getRetryCount()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.PAID);
        assertThat(result.getGenStartedAt()).isNull();
        assertThat(result.getGenCompletedAt()).isNull();
        assertThat(result.getGenMinutes()).isNull();
        assertThat(result.getFailureStage()).isNull();
    }
}
