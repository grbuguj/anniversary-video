# 🎬 기념일 인생 영상 서비스 — 백엔드

> 부모님 사진 → AI 애니메이션 → 감동 영상 | 29,900원 단일 상품 | 1인 운영

## 현재 상태

| 항목 | 상태 |
|---|---|
| 백엔드 API | ✅ 완성 |
| 관리자 대시보드 | ✅ 완성 |
| 고객 랜딩 페이지 | ✅ 완성 |
| 테스트 (11개) | ✅ ALL PASS |
| Docker 배포 세트 | ✅ 완성 |
| AWS/토스/RunwayML 키 연결 | ⬜ 키 입력 필요 |
| 운영 배포 | ⬜ 대기 |

---

## 빠른 시작 (로컬)

```bash
git clone <repo>
cd anniversary-video
./gradlew bootRun
# → http://localhost:8081 (랜딩페이지)
# → http://localhost:8081/admin/index.html (관리자, admin/admin1234)
# → http://localhost:8081/health (헬스체크)
```

---

## 운영 배포 (Docker)

### 1단계 — 환경변수 설정

```bash
cp .env.example .env
vi .env  # 아래 5개 키 필수 입력
```

| 변수 | 어디서 발급 | 필수 |
|---|---|---|
| `AWS_ACCESS_KEY` + `AWS_SECRET_KEY` | AWS IAM | ✅ |
| `AWS_S3_BUCKET` | S3 버킷 이름 | ✅ |
| `TOSS_CLIENT_KEY` + `TOSS_SECRET_KEY` | [토스페이먼츠 대시보드](https://developers.tosspayments.com) | ✅ |
| `RUNWAYML_API_KEY` | [RunwayML](https://app.runwayml.com) | ✅ |
| `SOLAPI_API_KEY` + `SOLAPI_API_SECRET` + `SOLAPI_SENDER` | [솔라피](https://solapi.com) | 선택 |
| `SLACK_WEBHOOK_URL` | Slack App | 선택 |
| `ADMIN_PASSWORD` | 직접 설정 | ✅ (반드시 변경) |

### 2단계 — Docker 실행

```bash
docker-compose up -d --build

# 로그 확인
docker-compose logs -f app

# 헬스체크
curl http://localhost:8081/health
```

### 3단계 — AWS S3 버킷 설정

```
버킷 정책 (CORS 설정):
[
  {
    "AllowedHeaders": ["*"],
    "AllowedMethods": ["PUT", "GET"],
    "AllowedOrigins": ["https://your-domain.com"],
    "ExposeHeaders": []
  }
]
```

### 4단계 — 토스페이먼츠 웹훅 등록

```
대시보드 → 웹훅 → https://your-domain.com/api/payments/webhook
이벤트: PAYMENT_STATUS_CHANGED
```

---

## API 엔드포인트

### 고객 API (공개)

| Method | URL | 설명 |
|---|---|---|
| `POST` | `/api/orders` | 주문 생성 + S3 업로드 URL 발급 |
| `GET` | `/api/orders/{id}/status` | 주문 상태 조회 |
| `POST` | `/api/orders/{id}/download-url` | 다운로드 URL 재발급 (72h) |
| `GET` | `/api/orders/payment-config` | 토스 clientKey 조회 |
| `POST` | `/api/payments/confirm` | 결제 승인 |
| `POST` | `/api/payments/webhook` | 토스 웹훅 수신 |
| `GET` | `/health` | 헬스체크 |

### 관리자 API (Basic Auth)

```bash
# 기본 인증: admin / {ADMIN_PASSWORD}
curl -u admin:password http://localhost:8081/admin/dashboard
```

| Method | URL | 설명 |
|---|---|---|
| `GET` | `/admin/orders` | 전체 주문 목록 |
| `GET` | `/admin/orders/{id}` | 주문 상세 |
| `PUT` | `/admin/orders/{id}/status` | 상태 수동 변경 |
| `PUT` | `/admin/orders/{id}/memo` | 메모 저장 |
| `POST` | `/admin/orders/{id}/regenerate` | 영상 재생성 |
| `GET` | `/admin/dashboard` | 통계 |

---

## 주문 처리 플로우

```
고객 → POST /api/orders → S3 Presigned URL 12개
     → S3 직접 업로드 (PUT)
     → 토스페이먼츠 결제 위젯
     → POST /api/payments/confirm
       → 금액 검증 → 토스 승인 API
       → Order: PENDING → PAID
       → @Async VideoGenerationService.start()
         → Order: PROCESSING
         → RunwayML API × 12장 (클립 생성)
         → FFmpeg 합치기 + BGM + 1080p
         → S3 업로드
         → Order: COMPLETED
         → SMS 다운로드 링크 발송
```

---

## 자동화 (스케줄러)

| 스케줄 | 동작 |
|---|---|
| 매시간 | PENDING 24h 초과 → FAILED 자동만료 |
| 매 10분 | PROCESSING 2h 초과 → 자동 재시도 (최대 2회) → FAILED |

---

## 원가 분석

| 항목 | 비용 |
|---|---|
| RunwayML (12장 × 5초) | ~$6 (약 8,700원) |
| AWS S3 (저장 + 전송) | ~100원 |
| 솔라피 SMS | ~20원 |
| **합계** | **~8,820원** |
| **판매가** | **29,900원** |
| **마진** | **21,080원 (70.5%)** |

---

## 기술 스택

- **Spring Boot 3.2.5** / Java 17
- **Spring Security** (Basic Auth 관리자)
- **Spring Data JPA** + H2(로컬) / MySQL(운영)
- **AWS S3** — 사진·클립·결과 영상 저장
- **RunwayML Gen-3 Turbo** — 이미지→영상
- **FFmpeg** — 클립 합성 + BGM + 1080p 인코딩
- **토스페이먼츠** — 결제
- **솔라피** — 카카오/SMS 알림
- **Docker Compose** — MySQL 포함 원클릭 배포

---

## 테스트

```bash
./gradlew test

# 테스트 목록
# ✅ OrderServiceTest (5개)
# ✅ PaymentServiceTest (2개)
# ✅ OrderControllerTest (4개)
```
