# 프로젝트 현황 — 움직이는 사진관

> **작업 전 반드시 이 파일 먼저 읽고 시작할 것**
> 작업 완료 시 해당 항목 업데이트 필수
> 마지막 전체 코드 검토: 2026-02-24

---

## 전체 파일 구조 (실제 존재 확인 완료)

```
src/main/java/com/anniversary/video/
├── AnniversaryVideoApplication.java
├── config/
│   ├── AsyncConfig.java          ✅
│   ├── CorsConfig.java           ✅
│   ├── GlobalExceptionHandler.java ✅
│   ├── S3Config.java             ✅
│   └── SecurityConfig.java       ✅
├── controller/
│   ├── AdminController.java      ✅
│   ├── DevController.java        ✅ (@Profile("local"))
│   ├── HealthController.java     ✅
│   ├── OrderController.java      ✅
│   ├── PageController.java       ✅
│   └── PaymentController.java    ✅
├── domain/
│   ├── Order.java                ✅
│   └── OrderPhoto.java           ✅
├── dto/
│   ├── AdminOrderResponse.java   ✅
│   ├── OrderCreateRequest.java   ✅
│   ├── OrderCreateResponse.java  ✅
│   └── PaymentConfirmRequest.java ✅
├── repository/
│   ├── OrderPhotoRepository.java ✅
│   └── OrderRepository.java      ✅
└── service/
    ├── FfmpegService.java        ✅
    ├── NotificationService.java  ✅
    ├── OrderScheduler.java       ✅
    ├── OrderService.java         ✅
    ├── PaymentService.java       ✅
    ├── S3Service.java            ✅
    └── VideoGenerationService.java ✅

src/main/resources/
├── application.properties        ✅ (환경변수로 모두 외부화)
├── application-local.properties  ✅ (H2 파일DB)
├── application-prod.properties   ✅ (MySQL)
├── bgm/                          ← 서버사이드 BGM 없음 (static/bgm에 있음)
├── db/
│   ├── schema.sql                ✅
│   └── migration/
│       ├── V1__init_schema.sql   ✅
│       ├── V2__add_caption_to_order_photos.sql ✅
│       └── V3__add_intro_title_to_orders.sql   ✅
├── fonts/
│   └── NotoSansKR-Regular.ttf   ✅
├── logback-spring.xml            ✅
└── static/
    ├── index.html                ✅
    ├── status.html               ✅
    ├── bgm/
    │   └── bgm_01.mp3            ✅ (bgm_02, bgm_03은 없음)
    └── admin/
        └── index.html            ✅ (9.9KB, 대시보드 UI 있음)
```

---

## ✅ 실제 완성된 것 (코드 전체 확인)

### 도메인 / DB
- [x] Order 엔티티 (PENDING→PAID→PROCESSING→COMPLETED/FAILED), retryCount, introTitle, bgmTrack
- [x] OrderPhoto 엔티티 (s3Key, sortOrder, clipS3Key, caption)
- [x] Flyway 마이그레이션 V1(초기스키마) V2(caption) V3(intro_title)
- [x] OrderRepository — 상태별 조회, stuck 감지, 이어하기, 웹훅, 통계 쿼리 전부 구현
- [x] OrderPhotoRepository — findByOrderIdOrderBySortOrder
- [x] local: H2 파일DB / prod: MySQL 프로파일 분리, Flyway on/off 분리

### 서비스
- [x] OrderService — 주문생성 (PROCESSING 중복방지 Rate limit + 동일 이름+전화 이어하기 감지), 상태전환, 다운로드 URL 재발급
- [x] PaymentService — 포트원 V2 결제조회+금액위변조검증, 취소/환불, 웹훅 처리
- [x] S3Service — presigned PUT/GET, URL→S3 다운로드, 로컬→S3 업로드, S3→로컬 다운로드
- [x] VideoGenerationService — RunwayML gen3a_turbo 호출, 병렬 클립 생성(clipTaskExecutor 4개), 재시도 3회(10초/30초 백오프), 10분 폴링 타임아웃
- [x] FfmpegService — 인트로 클립(fadeIn 텍스트, 폰트 없으면 검정화면 fallback), concat, BGM 삽입(없으면 무음 fallback), 1920x1080 최종인코딩, S3업로드, tmp 작업 디렉터리 cleanup
- [x] NotificationService — 솔라피 HMAC-SHA256 인증, SMS 4종(주문확인/완성/업로드리마인더/실패), Slack 웹훅. apiKey="placeholder"면 로그만 출력
- [x] OrderScheduler — PENDING 24h 자동만료(매시 정각), PAID 2h 리마인더(매시 30분), /tmp 3h 정리(매시 20분), PROCESSING 2h stuck→자동재시도 2회→최종실패(매 10분)

### 컨트롤러
- [x] OrderController — 주문생성, 상태조회, 업로드URL발급, 업로드완료(BGM저장 포함), 주문취소, 다운로드URL재발급, BGM목록, 포트원 설정
- [x] PaymentController — 결제검증, 포트원 웹훅, 결제취소
- [x] AdminController — 주문목록(상태필터), 상세, 상태수동변경(COMPLETED 시 URL 자동발급), 메모, 재생성, URL재발급, 대시보드통계, 취소/환불
- [x] DevController (@Profile("local")) — 결제스킵, 업로드스킵(더미 OrderPhoto 생성), 영상생성직접트리거
- [x] PageController — /payment/success, /payment/fail, /status 라우팅
- [x] HealthController

### 설정
- [x] AsyncConfig — videoTaskExecutor(corePool=1, queue=20), clipTaskExecutor(corePool=4, queue=100)
- [x] SecurityConfig — /admin/** ROLE_ADMIN, /api/** 퍼블릭, CSRF 예외(/api/**, /admin/**, /h2-console/**), Spring 기본 폼 로그인
- [x] S3Config — S3Client, S3Presigner 빈 등록
- [x] CorsConfig — /api/** CORS 허용 (운영 시 실제 도메인으로 제한 필요)
- [x] GlobalExceptionHandler — Validation/IllegalArg/IllegalState/Runtime 예외 처리

### 프론트엔드
- [x] index.html — 랜딩(히어로/특징/프로세스), 주문폼, 포트원 V2 결제, 결제확인 모달, 결제 성공 리다이렉트 처리, 업로드 가이드, 벌크 업로드(3열 그리드), 드래그 순서변경(ghost 이미지 제거), BGM 선택+미리듣기, 업로드완료 확인 모달, 업로드 중 UI 락(isUploading), 제작 완료 폴링(15초), 이어하기 모달, DEV 모드, 플로우 스텝바, 완료/실패 화면
- [x] status.html — SMS 링크로 접근 시 상태 확인 페이지
- [x] admin/index.html — 관리자 대시보드 (9.9KB, 통계카드 + 주문목록 + 상태변경 UI)
- [x] bgm/bgm_01.mp3

---

## ❌ 없거나 미완성인 것

| 항목 | 상태 | 비고 |
|------|------|------|
| bgm_02.mp3, bgm_03.mp3 | ❌ 파일 없음 | BGM_LIST에 3개 등록돼 있는데 파일은 bgm_01만 있음. 선택해도 bgm_01로 fallback 됨 |
| RunwayML API 크레딧 | ❌ 미구매 | dev.runwayml.com 크레딧 없으면 클립 생성 전체 불가. $10 최소 구매 필요 |
| 포트원 실계좌 연동 | ❌ 미확인 | PORTONE_STORE_ID, PORTONE_CHANNEL_KEY, PORTONE_API_SECRET 환경변수 설정 여부 불명 |
| 솔라피 발신번호 인증 | ❌ 미확인 | SOLAPI_SENDER 환경변수 + 발신번호 사전등록 여부 불명 |
| 환경변수 전체 | ❌ 미설정 | application.properties 모두 ${ENV_VAR} 방식. 로컬 실행 위한 .env 또는 IDE 환경변수 설정 필요 |
| 배포 서버 | ❌ 미구성 | EC2/Railway 등 서버 없음. FFmpeg 설치, MySQL, 환경변수 세팅 필요 |
| CloudFront 배포 | ❌ 미구성 | cloudfront.domain 설정 필요. 현재 presigned S3 URL 직접 사용 중 |
| 엔드투엔드 테스트 | ❌ 미진행 | 실결제→업로드→RunwayML→FFmpeg→SMS 전체 플로우 테스트 필요 |

---

## ⚠️ 알려진 문제 / 주의사항

| 항목 | 내용 |
|------|------|
| caption 미입력 | 프론트에서 업로드 시 `caption: ''` 고정 전송. 자막 기능 쓰려면 입력 UI 추가 필요 |
| CorsConfig allowedOriginPatterns("*") | 운영 배포 전 실제 도메인으로 좁혀야 함 |
| SecurityConfig 기본 로그인 폼 | Spring 기본 폼(/login) 사용 중. 관리자 로그인 UI가 기본 Spring 화이트라벨 페이지임 |
| V1 schema.sql vs Flyway | `db/schema.sql`이 따로 있고 Flyway `V1__init_schema.sql`도 있음. local(H2)은 Flyway off → schema.sql 적용되는지 확인 필요 |
| RunwayML promptText | 영어 고정. 한국 노인 사진에 최적화된 프롬프트로 튜닝 여지 있음 |
| BGM fallback 로직 | bgmTrack 못 찾으면 bgm_01로 fallback → bgm_01도 없으면 무음 오디오 생성 (정상 동작) |

---

## 다음 작업 우선순위

1. **로컬 실행 확인** — 환경변수(.env 또는 IDE) 세팅하고 실제 서버 뜨는지 확인
2. **bgm_02.mp3, bgm_03.mp3 파일 추가** — `src/main/resources/static/bgm/`에 넣기
3. **RunwayML 크레딧 구매** — dev.runwayml.com ($10) → 클립 생성 실제 테스트
4. **포트원 실계좌 연동 + 결제 테스트** — 환경변수 설정 후 실결제 플로우 확인
5. **솔라피 발신번호 등록** — SMS 발송 테스트
6. **엔드투엔드 테스트** — 전체 플로우 1회 완주
7. **배포** — 서버, FFmpeg 설치, MySQL, 환경변수 설정

---

_마지막 업데이트: 2026-02-24 (전체 코드 직접 확인)_
