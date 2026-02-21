package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class NotificationService {

    @Value("${solapi.api-key}")
    private String apiKey;

    @Value("${solapi.api-secret}")
    private String apiSecret;

    @Value("${solapi.sender}")
    private String sender;

    @Value("${app.base-url:https://your-domain.com}")
    private String baseUrl;

    @Value("${slack.webhook-url:}")
    private String slackWebhookUrl;

    // ── 주문 접수 알림 ────────────────────────────────────────────────────
    public void sendOrderConfirmation(Order order) {
        String text = String.format(
                "[기념일 영상] 주문 접수 완료! 🎬\n\n" +
                "안녕하세요 %s님,\n" +
                "주문번호 #%d 이 정상 접수되었습니다.\n\n" +
                "• 상품: 프리미엄 인생 영상 (16:9 · 1080p)\n" +
                "• 금액: 29,900원\n" +
                "• 제작 예정: 24시간 이내\n\n" +
                "완성되면 바로 알림 드릴게요 💕",
                order.getCustomerName(), order.getId()
        );
        sendSms(order.getCustomerPhone(), text);
        sendSlack(String.format("✅ 새 주문 #%d — %s (%s)",
                order.getId(), order.getCustomerName(), order.getCustomerPhone()));
    }

    // ── 영상 완성 알림 ────────────────────────────────────────────────────
    public void sendCompletionAlert(Order order, String downloadUrl) {
        String text = String.format(
                "[기념일 영상] 영상이 완성되었어요! 🎉\n\n" +
                "%s님의 소중한 순간을 담은 영상이 완성되었습니다.\n\n" +
                "📥 다운로드 링크 (72시간 유효):\n%s\n\n" +
                "진행 상황 확인: " + baseUrl + "/status?orderId=" + order.getId() + "\n\n" +
                "행사에서 소중하게 사용해 주세요 💕",
                order.getCustomerName(), downloadUrl
        );
        sendSms(order.getCustomerPhone(), text);
        sendSlack(String.format("🎬 영상 완성 #%d — %s", order.getId(), order.getCustomerName()));
    }

    // ── 업로드 리마인더 (사진 업로드 안 한 고객) ────────────────────────────
    public void sendUploadReminder(Order order) {
        String text = String.format(
                "[기념일 영상] 사진 업로드가 남아있어요 📸\n\n" +
                "%s님, 결제는 완료되었지만 아직 사진 업로드를 안 하셨네요.\n\n" +
                "사진을 업로드해야 영상 제작이 시작됩니다.\n" +
                "아래 링크로 접속해 주세요:\n" +
                "%s/?resume=%d\n\n" +
                "문의: 카카오톡 @기념일영상",
                order.getCustomerName(), baseUrl, order.getId()
        );
        sendSms(order.getCustomerPhone(), text);
        log.info("업로드 리마인더 SMS - orderId: {}", order.getId());
    }

    // ── 실패 알림 (관리자 슬랙) ──────────────────────────────────────────
    public void sendFailureAlert(Order order) {
        String msg = String.format(
                "🚨 영상 생성 실패!\n주문 #%d — %s (%s)\n메모: %s\n관리자 확인 필요",
                order.getId(), order.getCustomerName(),
                order.getCustomerPhone(),
                order.getAdminMemo() != null ? order.getAdminMemo() : "-"
        );
        log.error(msg);
        sendSlack(msg);
    }

    // ── 솔라피 SMS ────────────────────────────────────────────────────────
    private void sendSms(String to, String text) {
        if (apiKey.equals("placeholder") || apiKey.isBlank()) {
            log.info("[SMS 미설정] to={}, 내용 앞30자: {}", to, text.substring(0, Math.min(30, text.length())));
            return;
        }
        try {
            String date = Instant.now().toString();
            String salt = UUID.randomUUID().toString().replace("-", "");
            String signature = hmacSha256(date + salt, apiSecret);

            Map<String, Object> body = Map.of(
                    "message", Map.of("to", to, "from", sender, "text", text)
            );
            String auth = String.format("HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s",
                    apiKey, date, salt, signature);

            WebClient.builder().baseUrl("https://api.solapi.com").build()
                    .post().uri("/messages/v4/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", auth)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            r -> log.info("SMS 성공 → {}", to),
                            e -> log.error("SMS 실패 → {}: {}", to, e.getMessage())
                    );
        } catch (Exception e) {
            log.error("SMS 예외: {}", e.getMessage());
        }
    }

    // ── 슬랙 웹훅 ────────────────────────────────────────────────────────
    private void sendSlack(String message) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
            log.info("[Slack 미설정] {}", message);
            return;
        }
        try {
            WebClient.create().post()
                    .uri(slackWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", message))
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            r -> log.debug("Slack 발송 완료"),
                            e -> log.warn("Slack 발송 실패: {}", e.getMessage())
                    );
        } catch (Exception e) {
            log.warn("Slack 예외: {}", e.getMessage());
        }
    }

    private String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
