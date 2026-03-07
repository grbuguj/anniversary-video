# 🎬 시간의 사진관 — 프리미엄 인생 영상 서비스

> 소중한 사진을 AI로 살아 움직이는 감동 영상으로 | 29,900원 | 1인 운영

**사이트:** https://timephoto.kr  
**카카오 채널:** @시간의사진관

---

## 현재 상태 (2026-03-08)

| 항목 | 상태 | 비고 |
|---|---|---|
| 백엔드 API | ✅ 완성 | Spring Boot 3.2.5 / Java 17 |
| 고객 랜딩 페이지 | ✅ 완성 | 반응형, 다크 프리미엄 디자인 |
| 관리자 대시보드 | ✅ 완성 | /admin |
| 결제 (포트원 V2 + KG이니시스) | ✅ 테스트 연동 완료 | 실연동 심사 대기 중 |
| AI 영상 생성 (xAI Grok Imagine) | ✅ 완성 | 10장 병렬 생성, 클립당 $0.30 |
| FFmpeg 영상 합치기 | ✅ 완성 | 인트로 + 아웃트로 + 10클립 + BGM |
| SMS 알림 (솔라피) | ✅ 완성 | 결제/리마인더/완성 알림 |
| EC2 배포 | ✅ 완성 | Docker Compose, t3.medium, 서울 |
| SSL (Let's Encrypt) | ✅ 완성 | 자동 갱신 |
| DNS (가비아) | ✅ 완성 | timephoto.kr → Elastic IP |
| 분석 파이프라인 | ✅ 완성 | 이벤트·퍼널·SLA 자동 집계 |
| FAQ 섹션 | ✅ 완성 | 7개 아코디언, 주문 폼 위 |
| 카카오톡 채팅 버튼 | ✅ 완성 | 우측 하단 플로팅 |
| 환불 정책 상세화 | ✅ 완성 | 100% 만족 보장 3단계 |
| NHN KCP 에스크로 | ✅ 확인증 발급 | 제 A11-260306-9597 호 |
| 포트원 실연동 (KG이니시스) | ⏳ 심사 중 | 바로오픈 MID 발급됨 |
| 포트원 실연동 (카카오페이) | ⏳ 심사 중 | 전자계약 진행 필요 |
| 카카오 비즈채널 | ⏳ 설정 중 | 채널 생성 완료, 공개 설정 완료 |
| 통신판매업 신고 | ⏳ 대기 | 에스크로 확인증 발급 완료, 신고 진행 필요 |
| 프롬프트 튜닝 | 🔧 진행 중 | 얼굴 보존 개선 필요 |

---

## 빠른 시작 (로컬)

```bash
git clone https://github.com/grbuguj/anniversary-video.git
cd anniversary-video
cp .env.example .env  # 키 입력
./gradlew bootRun
```

| URL | 설명 |
|---|---|
| http://localhost:8081 | 고객 랜딩페이지 |
| http://localhost:8081/admin | 관리자 대시보드 |
| http://localhost:8081/h2-console | H2 DB 콘솔 (로컬 전용) |
| http://localhost:8081/actuator/health | 헬스체크 |

---

## 운영 배포 (Docker)

### 인프라 구성

| 구성 | 스펙 |
|---|---|
| EC2 | t3.medium (4GB RAM), 서울 리전 |
| EBS | 20GB gp3 |
| Swap | 2GB |
| OS | Amazon Linux 2023 |
| Elastic IP | 43.202.235.146 |
| 도메인 | timephoto.kr (가비아) |
| SSL | Let's Encrypt (자동 갱신) |

### Docker 서비스 구성

| 서비스 | 이미지 | 포트 |
|---|---|---|
| app | Spring Boot (직접 빌드) | 8081 (내부) |
| db | MySQL 8.0 | 3306 |
| nginx | nginx:alpine | 80, 443 |
| certbot | certbot/certbot | - |

### 배포 방법

```bash
# EC2 접속
ssh -i timephoto_key.pem ec2-user@43.202.235.146

# 프로젝트
cd /home/ec2-user/anniversary-video

# 환경변수 설정
cp .env.example .env && nano .env

# 배포
sudo docker compose up -d --build

# 로그 확인
sudo docker exec anniversary-video-app-1 tail -f /var/log/anniversary/app.log

# 업데이트 배포
git pull && sudo docker compose down && sudo docker compose up -d --build
```

### 환경변수 (.env)

| 변수 | 발급처 | 필수 |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | 직접 설정 | ✅ |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` | AWS IAM | ✅ |
| `AWS_S3_BUCKET` / `AWS_REGION` | S3 (eu-north-1) | ✅ |
| `PORTONE_STORE_ID` / `PORTONE_CHANNEL_KEY` / `PORTONE_API_SECRET` | [포트원](https://admin.portone.io) | ✅ |
| `XAI_API_KEY` | [xAI](https://console.x.ai) | ✅ |
| `SOLAPI_API_KEY` / `SOLAPI_API_SECRET` / `SOLAPI_SENDER` | [솔라피](https://solapi.com) | ✅ |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 직접 설정 | ✅ |
| `DOMAIN` | 도메인명 | ✅ |
| `SLACK_WEBHOOK_URL` | Slack | 선택 |
| `CLOUDFRONT_DOMAIN` | CloudFront | 선택 |

---

## 주문 처리 플로우

```
고객 → 이름/연락처/이메일 입력 → 결제 (포트원 V2 + KG이니시스)
    → 사진 10장 업로드 (S3 Presigned URL)
    → 인트로/아웃트로 제목 설정
    → BGM 선택
    → 영상 제작 시작
      → xAI Grok Imagine Video API × 10장 (5쓰레드 병렬)
      → FFmpeg: 인트로 → 10클립 → 아웃트로 → BGM → 1080p 최종 인코딩
      → S3 업로드
    → 완성 SMS 발송 (다운로드 링크, 72시간 유효)
```

---

## API 엔드포인트

### 고객 API (`/api/**`)

| Method | URL | 설명 |
|---|---|---|
| `POST` | `/api/orders` | 주문 생성 |
| `GET` | `/api/orders/t/{token}/status` | 상태 조회 |
| `GET` | `/api/orders/t/{token}/upload-urls` | 업로드 URL 발급 |
| `POST` | `/api/orders/t/{token}/upload-complete` | 업로드 완료 → 영상 생성 |
| `POST` | `/api/orders/t/{token}/cancel` | 주문 취소 |
| `POST` | `/api/orders/t/{token}/download-url` | 다운로드 URL 재발급 |
| `GET` | `/api/orders/bgm-list` | BGM 목록 |
| `GET` | `/api/orders/payment-config` | 포트원 설정 |
| `POST` | `/api/payments/confirm` | 결제 검증 |
| `POST` | `/api/payments/webhook` | 포트원 웹훅 |
| `POST` | `/api/events` | 프론트 이벤트 수신 |

### 관리자 API (`/admin/**`)

| Method | URL | 설명 |
|---|---|---|
| `GET` | `/admin/orders` | 주문 목록 |
| `GET` | `/admin/orders/{id}` | 주문 상세 |
| `PUT` | `/admin/orders/{id}/status` | 상태 변경 |
| `POST` | `/admin/orders/{id}/regenerate` | 영상 재생성 |
| `GET` | `/admin/dashboard` | 통계 대시보드 |

---

## 원가 분석

| 항목 | 비용 |
|---|---|
| xAI Grok Imagine (10장 × $0.30) | ~$3.00 (~4,300원) |
| AWS EC2 t3.medium (서울) | ~$38/월 |
| AWS S3 + 전송 | ~100원/건 |
| 솔라피 SMS × 2~3건 | ~60원/건 |
| **건당 변동비** | **~4,460원** |
| **판매가** | **29,900원** |
| **건당 마진** | **~25,440원 (85%)** |

---

## Grok 프롬프트

```
Gentle, natural subtle movement. Soft cinematic atmosphere.
Warm nostalgic tone. High detail, sharp focus, fine textures.
Preserve the exact facial identity and features.
Do NOT morph, distort, or alter any face.
```

---

## DB 스키마

Flyway V1~V7 마이그레이션 관리.

| 테이블 | 설명 |
|---|---|
| `orders` | 주문 (상태·결제·인트로/아웃트로·생성시간추적) |
| `order_photos` | 사진별 S3 키 + 클립 키 + 캡션 |
| `order_events` | 프론트/서버 이벤트 로그 |
| `daily_funnel_metrics` | 일별 퍼널 집계 |
| `hourly_sla_metrics` | 시간별 SLA 집계 |

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| 프레임워크 | Spring Boot 3.2.5 / Java 17 |
| 인증 | Spring Security (Form Login) |
| DB | H2 (로컬) / MySQL 8.0 (운영) |
| 마이그레이션 | Flyway V1~V7 |
| 스토리지 | AWS S3 (Presigned URL) |
| AI 영상 | xAI Grok Imagine Video API |
| 영상 처리 | FFmpeg (인트로·아웃트로·합성·BGM·1080p) |
| 결제 | 포트원 V2 + KG이니시스 |
| 알림 | 솔라피 SMS + Slack 웹훅 |
| 배포 | Docker Compose (EC2 t3.medium) |
| SSL | Let's Encrypt + Certbot |
| 모니터링 | Spring Actuator |

---

## 사업자 정보

| 항목 | 내용 |
|---|---|
| 상호 | 시간의사진관 |
| 대표 | 김재웅 |
| 사업자등록번호 | 292-32-01725 |
| 업태 | 정보통신업, 전문·과학 및 기술서비스업 |
| 종목 | 응용 소프트웨어 개발 및 공급업, 광고 대행업 |
| 주소 | 인천광역시 서구 솔빛로 13, 542동 2003호(청라동) |
| 개업일 | 2025-12-22 |
