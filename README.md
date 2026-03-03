# 🎬 움직이는 사진관 — 백엔드

> 부모님 사진 → AI 애니메이션 → 감동 영상 | 29,900원 단일 상품 | 1인 운영

## 현재 상태

| 항목 | 상태 |
|---|---|
| 백엔드 API | ✅ 완성 |
| 관리자 대시보드 | ✅ 완성 |
| 고객 랜딩 페이지 | ✅ 완성 |
| 결제 (포트원 V2) | ✅ 완성 |
| Docker 배포 세트 | ✅ 완성 |
| 분석 파이프라인 (이벤트·퍼널·SLA) | ✅ 완성 |
| 레이어 아키텍처 리팩토링 | ✅ 완성 |
| AWS / 포트원 / RunwayML 키 연결 | ⬜ 키 입력 필요 |
| 운영 배포 | ⬜ 대기 |

---

## 빠른 시작 (로컬)

```bash
git clone <repo>
cd anniversary-video
./gradlew bootRun
```

| URL | 설명 |
|---|---|
| http://localhost:8081 | 고객 랜딩페이지 |
| http://localhost:8081/status.html | 주문 상태 확인 |
| http://localhost:8081/admin/index.html | 관리자 대시보드 (Spring 로그인 후 접근) |
| http://localhost:8081/health | 헬스체크 |
| http://localhost:8081/h2-console | H2 DB 콘솔 (로컬 전용) |

로컬 환경은 H2 인메모리 DB + Flyway 비활성화 + `ddl-auto=update`로 동작합니다.

---

## 운영 배포 (Docker)

### 1단계 — 환경변수 설정

```bash
cp .env.example .env
vi .env
```

| 변수 | 발급처 | 필수 |
|---|---|---|
| `AWS_ACCESS_KEY` + `AWS_SECRET_KEY` | AWS IAM | ✅ |
| `AWS_S3_BUCKET` + `AWS_REGION` | S3 버킷 | ✅ |
| `PORTONE_STORE_ID` + `PORTONE_CHANNEL_KEY` + `PORTONE_API_SECRET` | [포트원 콘솔](https://admin.portone.io) | ✅ |
| `RUNWAYML_API_KEY` | [RunwayML](https://app.runwayml.com) | ✅ |
| `SOLAPI_API_KEY` + `SOLAPI_API_SECRET` + `SOLAPI_SENDER` | [솔라피](https://solapi.com) | 선택 (SMS) |
| `SLACK_WEBHOOK_URL` | Slack App | 선택 (알림) |
| `ADMIN_USERNAME` + `ADMIN_PASSWORD` | 직접 설정 | ✅ |
| `DB_USERNAME` + `DB_PASSWORD` | 직접 설정 | ✅ |
| `APP_BASE_URL` | 서비스 도메인 | ✅ |
| `CLOUDFRONT_DOMAIN` | CloudFront 배포 | 선택 |

### 2단계 — Docker 실행

```bash
docker-compose up -d --build

# 로그 확인
docker-compose logs -f app

# 헬스체크
curl http://localhost:8081/health
```

### 3단계 — S3 CORS 설정

```json
[
  {
    "AllowedHeaders": ["*"],
    "AllowedMethods": ["PUT", "GET"],
    "AllowedOrigins": ["https://your-domain.com"],
    "ExposeHeaders": []
  }
]
```

### 4단계 — 포트원 웹훅 등록

```
포트원 콘솔 → 웹훅 → https://your-domain.com/api/payments/webhook
이벤트: Transaction.Paid
```

---

## 주문 처리 플로우

```
고객 → POST /api/orders
       → 이어하기 감지 (동일 이름+전화번호 PAID 주문)
       → 신규 주문 생성 + S3 Presigned URL 발급
       → [이벤트] order_created

     → 프론트에서 포트원 결제 위젯 호출
     → POST /api/payments/confirm
       → 포트원 V2 결제 조회 → 금액 위변조 검증
       → Order: PENDING → PAID
       → SMS 주문 접수 알림
       → [이벤트] pay_success, notify_sent

     → GET /api/orders/{id}/upload-urls  (presigned URL 재발급)
     → S3 직접 업로드 (PUT × 10~15장)

     → POST /api/orders/{id}/upload-complete
       → OrderPhoto 저장 (caption 포함)
       → BGM 선택값 저장
       → [이벤트] upload_complete
       → @Async VideoGenerationService.startVideoGeneration()
         → Order: PAID → PROCESSING, genStartedAt 기록
         → [이벤트] gen_start
         → RunwayML API × N장 (병렬, 재시도 3회)
         → FFmpeg: 인트로 생성 → 클립 합성 → BGM → 16:9 1080p
         → S3 업로드
         → Order: PROCESSING → COMPLETED, genMinutes 계산
         → SMS 다운로드 링크 발송 (72h 유효)
         → [이벤트] gen_complete, notify_sent
         → (실패 시) failureStage 기록 + [이벤트] gen_fail
```

---

## API 엔드포인트

### 고객 API (`/api/**` — 공개)

| Method | URL | 설명 |
|---|---|---|
| `POST` | `/api/orders` | 주문 생성 + presigned URL 발급 |
| `GET` | `/api/orders/{id}/status` | 주문 상태 조회 |
| `GET` | `/api/orders/{id}/upload-urls` | 업로드 URL 재발급 (결제 후) |
| `POST` | `/api/orders/{id}/upload-complete` | 사진 업로드 완료 → 영상 생성 시작 |
| `POST` | `/api/orders/{id}/cancel` | PAID 주문 취소 (재주문 허용) |
| `POST` | `/api/orders/{id}/download-url` | 다운로드 URL 재발급 (만료 시) |
| `GET` | `/api/orders/bgm-list` | BGM 목록 조회 |
| `GET` | `/api/orders/payment-config` | 포트원 storeId·channelKey 조회 |
| `POST` | `/api/payments/confirm` | 결제 검증 (포트원 V2) |
| `POST` | `/api/payments/webhook` | 포트원 웹훅 수신 |
| `POST` | `/api/events` | 프론트 비콘 이벤트 수신 |
| `GET` | `/health` | 헬스체크 |

### 관리자 API (`/admin/**` — Spring Security Form Login)

| Method | URL | 설명 |
|---|---|---|
| `GET` | `/admin/orders` | 전체 주문 목록 (`?status=PAID` 필터 가능) |
| `GET` | `/admin/orders/{id}` | 주문 상세 (genMinutes·failureStage 포함) |
| `PUT` | `/admin/orders/{id}/status` | 상태 수동 변경 |
| `PUT` | `/admin/orders/{id}/memo` | 메모 저장 |
| `POST` | `/admin/orders/{id}/regenerate` | 영상 재생성 (retryCount 증가, 분석 필드 초기화) |
| `POST` | `/admin/orders/{id}/refresh-url` | 다운로드 URL 재발급 |
| `PUT` | `/admin/orders/{id}/cancel` | 결제 취소/환불 (포트원 연동) |
| `GET` | `/admin/dashboard` | 통계 대시보드 |

---

## 분석 파이프라인

### 이벤트 로깅

모든 주요 행동이 `order_events` 테이블에 비동기 기록됩니다.

| 이벤트 | 출처 | 시점 |
|---|---|---|
| `page_view` | front | 랜딩 페이지 방문 |
| `pay_start` | front | 결제 위젯 진입 |
| `order_created` | server | 주문 생성 |
| `pay_success` | server | 결제 검증 완료 |
| `upload_start` | front | 사진 업로드 시작 |
| `upload_complete` | server | 업로드 완료 처리 |
| `gen_start` | server | 영상 생성 시작 |
| `gen_complete` | server | 영상 생성 완료 |
| `gen_fail` | server | 영상 생성 실패 (failureStage 포함) |
| `notify_sent` | server | SMS/Slack 알림 발송 |
| `download_click` | front | 다운로드 링크 클릭 |

### 집계 테이블

| 테이블 | 스케줄 | 내용 |
|---|---|---|
| `daily_funnel_metrics` | 매일 01:05 | 일별 퍼널 (페이지뷰→주문→결제→업로드→생성→완료→실패) + 매출 |
| `hourly_sla_metrics` | 매시 10분 | 시간별 SLA (평균/최대/p95 생성 시간, 실패율, 재시도 횟수) |

---

## 자동화 (스케줄러)

| 스케줄 | 동작 |
|---|---|
| 매시 정각 | PENDING 24h 초과 → FAILED 자동만료 |
| 매시 10분 | 시간별 SLA 집계 (`hourly_sla_metrics`) |
| 매시 20분 | `/tmp/anniversary/` 3시간 이상 된 작업 디렉터리 정리 |
| 매시 30분 | PAID + 2~12h 경과 → 사진 업로드 리마인더 SMS |
| 매 10분 | PROCESSING 2h 초과 → 자동 재시도 (최대 2회) → 최종 FAILED |
| 매일 01:05 | 일별 퍼널 집계 (`daily_funnel_metrics`) |

---

## 프로젝트 구조

```
src/main/java/com/anniversary/video/
├── config/
│   ├── AsyncConfig.java          # videoTaskExecutor(1), clipTaskExecutor(4), eventLogExecutor(2)
│   ├── CorsConfig.java
│   ├── GlobalExceptionHandler.java  # 예외 5단계 세분화 (400/409/502/500)
│   ├── S3Config.java
│   └── SecurityConfig.java       # Form Login, /admin/** ROLE_ADMIN
├── controller/
│   ├── AdminController.java      # 관리자 CRUD + 대시보드 (OrderService 경유)
│   ├── BgmConstants.java         # BGM 메타 정보 상수
│   ├── DevController.java
│   ├── EventController.java      # 프론트 비콘 이벤트 수신
│   ├── HealthController.java
│   ├── OrderController.java      # 주문 생성·업로드·상태 조회 (얇은 컨트롤러)
│   ├── PageController.java
│   └── PaymentController.java    # 포트원 결제 검증·웹훅
├── domain/
│   ├── Order.java                # 주문 엔티티 + genStartedAt/genMinutes/failureStage
│   ├── OrderEvent.java           # 이벤트 로깅 엔티티
│   └── OrderPhoto.java           # 사진별 S3 키 + 클립 키 + 캡션
├── dto/
│   ├── AdminOrderResponse.java   # 관리자 응답 (분석 필드 포함)
│   ├── OrderCreateRequest.java
│   ├── OrderCreateResponse.java
│   └── PaymentConfirmRequest.java
├── repository/
│   ├── OrderEventRepository.java # 이벤트 조회 + 퍼널 집계 쿼리
│   ├── OrderRepository.java      # + SLA 집계 쿼리 (완료/실패/재시도/매출)
│   └── OrderPhotoRepository.java
└── service/
    ├── EventLoggingService.java   # 비동기 이벤트 저장 전담 (@Async)
    ├── FfmpegService.java         # 인트로 생성 + 클립 합성 + BGM + 1080p 인코딩
    ├── NotificationService.java   # 솔라피 SMS + Slack 웹훅 (브랜딩: 움직이는 사진관)
    ├── OrderScheduler.java        # 자동만료·리마인더·stuck 재시도·tmp 정리·퍼널/SLA 집계
    ├── OrderService.java          # 주문 CRUD + 업로드 완료 처리 + genMinutes 계산
    ├── PaymentService.java        # 포트원 V2 결제 검증·취소·웹훅 (OrderService 경유)
    ├── S3Service.java             # Presigned URL + 업/다운로드
    └── VideoGenerationService.java # RunwayML 클립 생성 (병렬) + failureStage 추적
```

---

## DB 스키마

Flyway migration V1~V4로 관리. 참조용 전체 스키마: `src/main/resources/db/schema.sql`

| 테이블 | 설명 |
|---|---|
| `orders` | 주문 (상태·결제·생성시간추적·실패단계) |
| `order_photos` | 사진별 S3 키 + 클립 키 + 캡션 |
| `order_events` | 프론트/서버 이벤트 로그 |
| `daily_funnel_metrics` | 일별 퍼널 집계 |
| `hourly_sla_metrics` | 시간별 SLA 집계 |

---

## 원가 분석

| 항목 | 비용 |
|---|---|
| RunwayML Gen-3 Turbo (10~15장 × 5초) | ~$6 (~8,700원) |
| AWS S3 (저장 + 전송) | ~100원 |
| 솔라피 SMS × 2건 | ~40원 |
| **합계** | **~8,840원** |
| **판매가** | **29,900원** |
| **마진** | **~21,060원 (70%)** |

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| 프레임워크 | Spring Boot 3.2.5 / Java 17 |
| 인증 | Spring Security (Form Login + InMemory) |
| DB | H2 (로컬) / MySQL 8.0 (운영) |
| 마이그레이션 | Flyway V1~V4 (운영만 활성화) |
| 스토리지 | AWS S3 (Presigned URL 기반) |
| AI 영상 | RunwayML Gen-3 Alpha Turbo |
| 영상 처리 | FFmpeg (인트로·합성·BGM·1080p) |
| 결제 | 포트원 V2 API |
| 알림 | 솔라피 SMS + Slack 웹훅 |
| 배포 | Docker Compose (app + MySQL) |
| 모니터링 | Spring Actuator (health, info, metrics) |

---

## 테스트

```bash
./gradlew test
```

| 테스트 | 파일 |
|---|---|
| OrderServiceTest (5개) | 주문 생성·이어하기·Rate Limit |
| PaymentServiceTest (2개) | 결제 검증·금액 위변조 |
| OrderControllerTest (4개) | API 엔드포인트 |
| OrderFlowIntegrationTest | 전체 플로우 통합 테스트 |

---

## 리팩토링 이력

6단계 리팩토링 완료.

| Phase | 내용 | 상태 |
|---|---|---|
| 1 | DB 스키마 정합성 — V4 migration + schema.sql 동기화 | ✅ |
| 2 | Domain 레이어 — Order 분석 필드 4개 추가, OrderEvent 신규 엔티티 | ✅ |
| 3 | Repository 레이어 — OrderEventRepository + SLA 집계 쿼리 4개 | ✅ |
| 4 | Service 레이어 — 레이어 경계 정리, EventLoggingService, 분석 파이프라인, 브랜딩 통일 | ✅ |
| 5 | Controller 레이어 — 비즈니스 로직 서비스 이동, EventController 신규, 버그 수정 | ✅ |
| 6 | Config — eventLogExecutor 추가, 예외 핸들러 5단계 세분화 | ✅ |

주요 개선: 레이어 경계 붕괴 수정 (AdminController·PaymentService에서 Repository 직접 호출 제거), DB 스키마-엔티티 불일치 해소, 비동기 이벤트 파이프라인 구축, 일별 퍼널·시간별 SLA 자동 집계, 브랜딩 "움직이는 사진관" 통일, 예외 처리 세분화 (400/409/502/500).
