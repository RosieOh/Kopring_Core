#!/usr/bin/env bash
# 즉시 발급 vs 대기열: 동시 사용자 수를 바꿔 가며 두 시나리오를 번갈아 실행하고, 결과를 표로 정리한다.
#
# 준비: docker compose --profile monitoring up -d && ./gradlew bootRun
# 실행: ./load-test/run-comparison.sh            (기본: 100·500·1000·2000명, 쿠폰 100장)
#       VUS_LIST="100 500" QUANTITY=50 ./load-test/run-comparison.sh
set -euo pipefail
cd "$(dirname "$0")"

# macOS 기본 열린 파일 수(256)로는 수천 개 동시 연결을 못 연다
ulimit -n 10240 2>/dev/null || true

QUANTITY=${QUANTITY:-100}
VUS_LIST=${VUS_LIST:-"100 500 1000 2000"}
OUT=results
mkdir -p "$OUT"
: > "$OUT/runs.txt"

echo "워밍업 (JIT 컴파일 — 결과에 넣지 않는다)"
k6 run -q -e VUS=200 -e QUANTITY="$QUANTITY" issue-direct.js > /dev/null 2>&1 || true
k6 run -q -e VUS=200 -e QUANTITY="$QUANTITY" issue-queue.js > /dev/null 2>&1 || true
sleep 5

for vus in $VUS_LIST; do
  for scenario in direct queue; do
    echo "실행: $scenario ${vus}명"
    start=$(date +%s)
    # 기준(threshold)을 넘으면 k6 가 0 이 아닌 값으로 끝나지만, 비교가 목적이라 계속 진행한다
    k6 run -q -e VUS="$vus" -e QUANTITY="$QUANTITY" --summary-export "$OUT/$scenario-$vus.json" "issue-$scenario.js" > /dev/null 2>&1 || true
    end=$(date +%s)
    echo "$scenario $vus $start $end" >> "$OUT/runs.txt"
    sleep 5 # 다음 실행 전에 서버 지표가 가라앉게 기다린다
  done
done

python3 summarize.py "$OUT" "$QUANTITY" | tee "$OUT/summary.md"
