#!/usr/bin/env bash
# 重新打包并以当前 APP_JAVA_OPTS / APP_SPRING_JSON / APP_MEM 重建 app 容器，然后预热 JIT。
# 注意：容器已退出时 `up --build` 可能复用旧容器，必须 --force-recreate。
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -q -DskipTests package
docker compose --profile app build -q app
docker compose --profile app up -d --force-recreate --wait app
# 预热：避免把 JIT 编译期的慢请求算进结果。D5 实测：只预热 20 秒时，紧接着的第一轮
# 600 次/秒有约 10% 被过载保护拒绝，充分预热后同样负载 100% 接收、P99 132 ms。
bench/run.sh warmup-1 ingest RATE=300 ENDPOINTS=1 DURATION=30s > /dev/null
bench/run.sh warmup-2 ingest RATE=600 ENDPOINTS=0 DURATION=20s > /dev/null
sed -i '/"label": "warmup-/d' target/bench/results.jsonl
echo "redeployed and warmed up"
