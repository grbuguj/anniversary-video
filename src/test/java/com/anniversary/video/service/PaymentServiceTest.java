package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @InjectMocks
    private PaymentService paymentService;

    @Mock private OrderService orderService;
    @Mock private NotificationService notificationService;
    @Mock private EventLoggingService eventLoggingService;

    @Test
    @DisplayName("이미 결제된 주문 중복 처리 시 예외")
    void confirmPayment_alreadyPaid_throws() {
        Order order = Order.builder()
                .id(1L).amount(29900).status(Order.OrderStatus.PAID).build();
        given(orderService.findById(1L)).willReturn(order);

        assertThatThrownBy(() -> paymentService.confirmPayment("payKey", "1", 29900))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 처리된 주문");
    }

    @Test
    @DisplayName("COMPLETED 주문 취소 불가")
    void cancelPayment_completed_throws() {
        Order order = Order.builder()
                .id(1L).amount(29900).status(Order.OrderStatus.COMPLETED).build();
        given(orderService.findById(1L)).willReturn(order);

        assertThatThrownBy(() -> paymentService.cancelPayment(1L, "테스트 취소"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완료된 주문");
    }

    @Test
    @DisplayName("결제키 없는 주문 취소 — 포트원 호출 없이 FAILED 처리")
    void cancelPayment_noPaymentKey_markedFailed() {
        Order order = Order.builder()
                .id(1L).amount(29900).status(Order.OrderStatus.PENDING)
                .paymentKey(null).build();
        given(orderService.findById(1L)).willReturn(order);

        var result = paymentService.cancelPayment(1L, "고객 요청 취소");

        assertThat(result.get("status")).isEqualTo("CANCELLED");
        then(orderService).should().markAsFailed(eq(1L), argThat(s -> s.contains("취소")));
    }
}
