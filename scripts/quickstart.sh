#!/usr/bin/env bash
# 一键启动：只需要 Docker（含 Compose v2）与 openssl、curl。
#   1. 没有 .env 时按 .env.example 生成，并填入随机口令与主密钥
#   2. 从源码构建镜像并启动 MySQL、Redis、Relay（首次构建需几分钟）
#   3. 创建一个演示租户，打印控制台地址与 API Key
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  sed -e "s|^RELAY_DB_PASSWORD=.*|RELAY_DB_PASSWORD=$(openssl rand -hex 16)|" \
      -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=$(openssl rand -hex 16)|" \
      -e "s|^RELAY_ADMIN_TOKEN=.*|RELAY_ADMIN_TOKEN=$(openssl rand -hex 24)|" \
      -e "s|^RELAY_MASTER_KEY=.*|RELAY_MASTER_KEY=$(openssl rand -base64 32)|" \
      -e "s|^GRAFANA_ADMIN_PASSWORD=.*|GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 12)|" \
      .env.example > .env
  echo "已生成 .env（随机口令，仅本机使用）"
fi

echo "构建并启动（首次需要几分钟）…"
docker compose --profile app up -d --build --wait

admin_token=$(grep '^RELAY_ADMIN_TOKEN=' .env | cut -d= -f2-)
response=$(curl -sf -X POST http://127.0.0.1:8080/api/admin/tenants \
  -H "Authorization: Bearer ${admin_token}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-$(date +%s)\",\"ingestQps\":200}")
api_key=$(printf '%s' "$response" | sed -E 's/.*"apiKey":"([^"]+)".*/\1/')

cat <<EOF

Relay 已启动。
  控制台：  http://localhost:8080/console/
  API Key： ${api_key}
  （只显示这一次；它也能直接调用 /api/v1/*。进入控制台后点“演示”一键生成事件。）

停止：docker compose --profile app stop
清除数据：docker compose --profile app down -v
EOF
