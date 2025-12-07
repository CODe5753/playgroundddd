#!/usr/bin/env sh

set -e

BODY='{"approvalId":"APP-CLI-'$(date +%s)'","amount":100.00,"phoneNumber":"010-1234-5678","message":"cli"}'
echo "POST /approve with payload: $BODY"
HTTP_CODE=$(curl -s -o /tmp/approve.out -w "%{http_code}" -H "Content-Type: application/json" -d "$BODY" http://localhost:8080/approve)

echo "HTTP status: $HTTP_CODE"
echo "Response body:"
cat /tmp/approve.out
echo ""

if [ "$HTTP_CODE" -ge 500 ]; then
  echo "휴리스틱 등 예외 응답(500 계열)일 수 있습니다. 앱 로그에서 HeuristicCompletionException을 확인하세요:"
  docker compose logs app | grep -i HeuristicCompletionException || true
fi
