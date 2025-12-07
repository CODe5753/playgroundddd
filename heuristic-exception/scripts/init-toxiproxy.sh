#!/usr/bin/env sh

set -e

API=${TOXIPROXY_API:-http://localhost:8474}
PROXY_NAME="mysql-ums-proxy"
curl_cmd="docker run --rm --network container:toxiproxy curlimages/curl:8.9.1 -s"

echo "toxiproxy 서버(${API})에 프록시를 생성/초기화합니다..."
$curl_cmd -XPOST "${API}/proxies" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"${PROXY_NAME}\",\"listen\":\"0.0.0.0:13306\",\"upstream\":\"mysql-ums:3306\"}" >/dev/null 2>&1 || true

# 기존 토식 제거
$curl_cmd -XDELETE "${API}/proxies/${PROXY_NAME}/toxics/reset-peer" >/dev/null 2>&1 || true
$curl_cmd -XDELETE "${API}/proxies/${PROXY_NAME}/toxics/latency-hit" >/dev/null 2>&1 || true

echo "워밍업 구간은 정상 연결, 이후 커밋 피크(약 70s 이후)를 겨냥해 reset_peer를 길게 1회 주입합니다."
echo "70s 이후 3초 동안 reset_peer(100%, timeout=2000ms)를 유지합니다."

(
  sleep 70
  name="reset-peer-long"
  echo "[toxiproxy] 70s 경과: ${name} 주입(3s 유지, timeout=2000, toxicity=1.0)"
  $curl_cmd -XPOST "${API}/proxies/${PROXY_NAME}/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${name}\",\"type\":\"reset_peer\",\"attributes\":{\"timeout\":2000},\"toxicity\":1.0}" >/dev/null
  sleep 3
  echo "[toxiproxy] ${name} 제거"
  $curl_cmd -XDELETE "${API}/proxies/${PROXY_NAME}/toxics/${name}" >/dev/null
) &

echo "프록시 초기화/주입 타이머를 백그라운드로 실행했습니다."
