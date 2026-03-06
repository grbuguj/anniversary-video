# 프로젝트 상태 — 시간의 사진관

> 최종 업데이트: 2026-03-06

---

## ✅ 완료된 작업

### 인프라
- [x] EC2 t3.medium (4GB RAM) 서울 리전 배포
- [x] EBS 20GB + Swap 2GB
- [x] Elastic IP 할당 (43.202.235.146)
- [x] Docker Compose (app + MySQL + Nginx + Certbot)
- [x] SSL 인증서 (Let's Encrypt)
- [x] 도메인 연결 (timephoto.kr → 가비아 DNS)
- [x] Dockerfile 수정 (/var/log/anniversary 디렉토리 생성, sh -c entrypoint)
- [x] ffmpeg 정적 바이너리 설치 (EC2 호스트)

### 결제
- [x] 포트원 V2 + KG이니시스 테스트 연동
- [x] 결제창 호출 정상 확인
- [x] 웹훅 URL 등록 (https://timephoto.kr/api/payments/webhook)
- [x] 이메일 필드 추가 (KG이니시스 V2 필수)
- [x] 포트원 계정 새로 생성 (시간의사진관 / bugs10613@gmail.com)

### 영상 생성
- [x] xAI Grok Imagine Video API 연동 (RunwayML에서 전환)
- [x] 10장 병렬 생성 (5쓰레드)
- [x] FFmpeg 인트로 + 클립 합치기 + BGM
- [x] 아웃트로 추가 (감사합니다 + 시간의 사진관)
- [x] S3 업로드 → 다운로드 URL 생성
- [x] 전체 플로우 테스트 성공 (주문 #7: 3.78분 완성)

### SMS
- [x] 솔라피 연동
- [x] 결제 접수 알림
- [x] 사진 업로드 리마인더
- [x] 영상 완성 알림 (일시적 연결 에러 발생, 재시도 필요)

### 프론트엔드
- [x] 반응형 다크 프리미엄 디자인
- [x] 주문폼: 이름, 연락처, 이메일
- [x] 사진 10장 드래그&드롭 업로드
- [x] 인트로/아웃트로 제목 입력 스텝 추가
- [x] BGM 3곡 선택
- [x] 3단계 스테퍼 (사진 → 제목 → 음악)
- [x] 결제 확인 모달
- [x] 영상 제작 진행 화면
- [x] 이어하기 모달 (기존 PAID 주문 감지)
- [x] footer 사업자 정보 (전자상거래법)
- [x] 환불/취소 정책

### DB 마이그레이션
- [x] V1: 초기 스키마 (orders, order_photos, order_events)
- [x] V2: caption 컬럼 추가
- [x] V3: intro_title 컬럼 추가
- [x] V4: analytics 스키마 (daily_funnel_metrics, hourly_sla_metrics)
- [x] V5: access_token 추가
- [x] V6: s3_key, s3key nullable 허용
- [x] V7: outro_title 컬럼 추가

---

## ⏳ 진행 중

### PG 실연동
- [ ] KG이니시스 바로오픈 — MID 발급됨 (MOI0995823), 키파일 등록 대기 (2영업일)
- [ ] KG이니시스 초기등록비 납부 — 문자 대기 중
- [ ] KG이니시스 signkey 설정 → 포트원 실연동 채널 생성
- [ ] 카카오페이 — 전자계약 진행 필요
- [ ] NHN KCP — 바로오픈 접수됨

### 카카오 비즈채널
- [ ] 채널 생성 완료 (시간의사진관)
- [ ] 공개 설정 완료
- [ ] 검색 허용 완료
- [ ] 홈 URL 설정 필요 (https://timephoto.kr)

### 통신판매업 신고
- [ ] 사업자등록증 ✅ (일반과세자, 292-32-01725)
- [ ] 구매안전서비스 이용확인증 — KG이니시스 에스크로 확인증 발급 대기
- [ ] 정부24 신고 → 호스트서버 소재지: 서울 강남구 테헤란로 231, 12층 (AWS 한국)

---

## 🔧 수정 필요

### 버그
- [ ] 업로드 카운트 불일치 — 상단 vs 하단 숫자 다름
- [ ] "10~20분 소요" → "8시간 내 완성 후 문자로 안내드립니다"로 변경
- [ ] favicon.ico 404 에러 (서비스 영향 없음)

### 개선
- [ ] Grok 프롬프트 튜닝 (얼굴 보존 강화, 현재 70점)
- [ ] footer에 연락처(전화번호) 추가
- [ ] footer에 통신판매신고번호 추가 (발급 후)
- [ ] og:image 소셜 공유 썸네일
- [ ] 샘플 영상 교체 (실 서비스 영상으로)
- [ ] FAQ 섹션 추가
- [ ] 리뷰 섹션 추가 (런칭 후)

---

## 📊 비용 구조

### 월 고정비
| 항목 | 비용 |
|---|---|
| EC2 t3.medium | ~$38/월 (~55,000원) |
| EBS 20GB | ~$1.6/월 (~2,300원) |
| Elastic IP | 무료 (연결 상태) |
| 도메인 (timephoto.kr) | ~15,000원/년 |
| **월 합계** | **~60,000원** |

### 건당 변동비
| 항목 | 비용 |
|---|---|
| xAI Grok (10클립) | ~$3.00 (~4,300원) |
| S3 저장/전송 | ~100원 |
| SMS 2~3건 | ~60원 |
| **건당 합계** | **~4,460원** |

### 손익분기
- 판매가: 29,900원
- 건당 마진: ~25,440원
- 손익분기: 월 2.4건 (월 고정비 ÷ 건당 마진)

---

## 🔑 계정/키 정보 (참조용)

| 서비스 | 계정 | 비고 |
|---|---|---|
| AWS | grbuguj | EC2 서울, S3 스톡홀름 |
| 포트원 | bugs10613@gmail.com | 시간의사진관 상점 |
| xAI | - | Grok Imagine Video API |
| 솔라피 | - | 발신번호: 010-4622-2849 |
| 가비아 | - | timephoto.kr 도메인 |
| 카카오비즈 | bugs0613@naver.com | 시간의사진관 채널 |
| GitHub | grbuguj | anniversary-video 레포 |

---

## 📁 주요 파일

```
├── Dockerfile                  # 멀티스테이지 빌드 (JDK→JRE + ffmpeg)
├── docker-compose.yml          # app + db + nginx + certbot
├── deploy.sh                   # EC2 원클릭 배포 스크립트
├── .env.example                # 환경변수 템플릿
├── nginx/conf.d/default.conf   # HTTPS 리버스 프록시
├── src/main/resources/
│   ├── static/index.html       # 고객 랜딩페이지 (SPA)
│   ├── static/admin/           # 관리자 대시보드
│   ├── static/bgm/             # BGM 파일 3곡
│   ├── application.properties  # 기본 설정 (환경변수 참조)
│   ├── application-prod.properties  # 운영 설정 (MySQL + Flyway)
│   └── db/migration/V1~V7     # Flyway 마이그레이션
└── src/main/java/.../
    ├── service/VideoGenerationService.java  # Grok API + 영상 생성
    ├── service/FfmpegService.java           # 인트로/아웃트로/합치기/BGM
    ├── service/OrderService.java            # 주문 CRUD
    ├── service/PaymentService.java          # 포트원 결제
    ├── service/NotificationService.java     # SMS/Slack
    └── controller/OrderController.java      # API 엔드포인트
```
