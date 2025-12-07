#!/usr/bin/env sh

set -e

BASE_URL=${BASE_URL:-http://host.docker.internal:8080}
VUS=${VUS:-60}
DURATION=${DURATION:-45s}

echo "앱/DB/proxy 컨테이너 기동 확인..."
docker compose up -d app toxiproxy mysql-approval mysql-ums >/dev/null

echo "toxiproxy 프록시 초기화 및 주입 타이머 설정..."
./scripts/init-toxiproxy.sh

echo "단건 사전 점검(정상 처리 가능 여부) ..."
./scripts/call-once.sh || true

echo "k6 부하 시작 (BASE_URL=${BASE_URL}, VUS=${VUS}, DURATION=${DURATION}) ..."
docker compose --profile stress run --rm \
  -e BASE_URL="${BASE_URL}" \
  -e VUS="${VUS}" \
  -e DURATION="${DURATION}" \
  k6

echo ""
echo "=== 요청 이후 상태 점검 ==="
./scripts/show-status.sh

echo ""
echo "=== HeuristicCompletionException 로그 검색 ==="
docker compose logs app | grep -i HeuristicCompletionException || echo "예외 로그 없음"
