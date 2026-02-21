package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @InjectMocks
    private PaymentService paymentService;

    @Mock private OrderService orderService;
    @Mock private VideoGenerationService videoGenerationService;
    @Mock private NotificationService notificationService;

    @Test
    @DisplayName("금액 위변조 감지 - 예외 발생")
    void confirmPayment_amountMismatch_throws() {
        Order order = Order.builder()
                .id(1L).amount(29900).status(Order.OrderStatus.PENDING).build();
        given(orderService.findById(1L)).willReturn(order);

        // 클라이언트가 금액을 1원으로 위변조
        assertThatThrownBy(() -> paymentService.confirmPayment("payKey", "1", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("금액 불일치");

        // 영상 생성 절대 호출 안 됨
        then(videoGenerationService).should(never()).startVideoGeneration(any());
    }

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
}
