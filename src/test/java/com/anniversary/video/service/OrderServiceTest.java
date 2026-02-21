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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Mock private OrderRepository orderRepository;
    @Mock private OrderPhotoRepository orderPhotoRepository;
    @Mock private S3Service s3Service;

    private OrderCreateRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new OrderCreateRequest();
        validRequest.setCustomerName("홍길동");
        validRequest.setCustomerPhone("01012345678");
        validRequest.setPhotoCount(12);
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
    @DisplayName("COMPLETED 상태 완료 처리")
    void markAsCompleted_success() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PROCESSING).build();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willReturn(order);

        Order result = orderService.markAsCompleted(1L, "results/1/final.mp4", "https://cdn.example.com/dl");

        assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.COMPLETED);
        assertThat(result.getDownloadUrl()).isEqualTo("https://cdn.example.com/dl");
    }

    @Test
    @DisplayName("실패 처리 - 메모 저장")
    void markAsFailed_withMemo() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PROCESSING).build();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willReturn(order);

        orderService.markAsFailed(1L, "RunwayML 타임아웃");

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertThat(order.getAdminMemo()).contains("RunwayML 타임아웃");
    }
}
