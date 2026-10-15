#!/usr/bin/env bash
# 跑一个压测场景并把结果追加到 target/bench/results.jsonl。
#
#   bench/run.sh <label> <ingest|delivery> [K=V ...]
#   例：bench/run.sh base-ingest-200 ingest RATE=200 ENDPOINTS=0
#       bench/run.sh base-fail30 delivery RATE=50 FAILURE_RATE=0.3
#
# 前提：docker compose --profile app up -d（Relay 跑在限制了 CPU/内存的容器里，RELAY_SINK_ENABLED=true）。
# k6 也在容器里跑（--cpus 1），与被测服务同一网络，不在本机安装任何东西。
set -euo pipefail
cd "$(dirname "$0")/.."

label=$1; script=$2; shift 2
out="target/bench/$label"
rm -rf "$out"; mkdir -p "$out"
admin_token=$(grep '^RELAY_ADMIN_TOKEN=' .env | cut -d= -f2-)
k6_env=(); for kv in "$@"; do k6_env+=(-e "$kv"); done
win_pwd=$(pwd -W 2>/dev/null || pwd)

# 记录被测容器的实际配置，防止“以为改了参数其实没生效”（D5 出现过一次）
{ docker inspect -f 'mem_limit={{.HostConfig.Memory}} cpus={{.HostConfig.NanoCpus}}' webhook-relay-app-1
  docker compose --profile app exec -T app sh -c 'echo "java_opts=$JAVA_OPTS"; echo "spring_json=$SPRING_APPLICATION_JSON"'
} > "$out/config.txt" 2>&1
curl -sf http://127.0.0.1:8080/actuator/prometheus > "$out/before.txt"

# 每秒采样各容器 CPU/内存
( while true; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null \
    | grep webhook-relay >> "$out/stats.csv" || true; done ) &
sampler=$!
trap 'kill $sampler 2>/dev/null || true' EXIT

MSYS_NO_PATHCONV=1 docker run --rm --network webhook-relay_default --cpus 1 \
  -e ADMIN_TOKEN="$admin_token" -e BASE=http://app:8080 "${k6_env[@]}" \
  -v "$win_pwd/bench/k6:/scripts:ro" -v "$win_pwd/$out:/out" \
  grafana/k6:1.0.0 run --quiet /scripts/"$script".js > "$out/k6.log" 2>&1 || echo "k6 exited non-zero (see $out/k6.log)"

drain=$(python -I bench/report.py drain "$out" "${DRAIN_TIMEOUT:-300}")
kill $sampler 2>/dev/null || true
# 积压类指标 5 秒刷新一次，等一个周期再取最终快照
sleep 6
curl -sf http://127.0.0.1:8080/actuator/prometheus > "$out/after.txt"

python -I bench/report.py summarize "$out" "$label" "$drain" | tee -a target/bench/results.jsonl
