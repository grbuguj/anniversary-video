#!/bin/bash
set -e

DOMAIN="timephoto.kr"
EMAIL="bugs10613@gmail.com"  # Let's Encrypt 알림용 (본인 이메일로 변경)

echo "=== 1. Docker & Docker Compose 설치 ==="
sudo dnf update -y
sudo dnf install -y docker git
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER

# docker compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

echo "=== 2. 프로젝트 클론 ==="
cd /home/ec2-user
if [ -d "anniversary-video" ]; then
  cd anniversary-video && git pull
else
  git clone https://github.com/grbuguj/anniversary-video.git
  cd anniversary-video
fi

echo "=== 3. .env 파일 확인 ==="
if [ ! -f .env ]; then
  cp .env.example .env
  echo ""
  echo "⚠️  .env 파일이 생성됐어. 실제 값 입력 필요!"
  echo "   nano .env"
  echo ""
  echo "입력 후 다시 이 스크립트 실행해."
  exit 1
fi

echo "=== 4. SSL 인증서 발급 ==="
mkdir -p nginx/certbot/conf nginx/certbot/www

if [ ! -f "nginx/certbot/conf/live/$DOMAIN/fullchain.pem" ]; then
  echo "--- SSL 인증서 없음. 발급 시작 ---"

  # 임시로 HTTP only nginx 설정
  cp nginx/conf.d/init-ssl.conf nginx/conf.d/default.conf.bak
  cp nginx/conf.d/init-ssl.conf nginx/conf.d/default.conf

  # app + db + nginx 시작 (HTTP only)
  sudo docker compose up -d --build app db nginx

  echo "--- 30초 대기 (서버 기동) ---"
  sleep 30

  # certbot으로 인증서 발급
  sudo docker compose run --rm certbot certonly \
    --webroot --webroot-path=/var/www/certbot \
    -d $DOMAIN -d www.$DOMAIN \
    --email $EMAIL --agree-tos --no-eff-email

  # SSL nginx 설정으로 교체
  cp nginx/conf.d/default.conf.bak nginx/conf.d/default.conf
  rm nginx/conf.d/default.conf.bak

  # nginx 재시작
  sudo docker compose restart nginx
  echo "✅ SSL 인증서 발급 완료!"
else
  echo "✅ SSL 인증서 이미 존재"
fi

echo "=== 5. 전체 서비스 시작 ==="
sudo docker compose up -d --build

echo ""
echo "============================="
echo "✅ 배포 완료!"
echo "https://$DOMAIN"
echo "============================="
echo ""
echo "상태 확인: sudo docker compose ps"
echo "로그 확인: sudo docker compose logs -f app"
echo "업데이트:  git pull && sudo docker compose up -d --build"
