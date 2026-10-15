// 公共的租户 / 模拟接收方 / 端点准备逻辑。所有地址与口令来自环境变量，不写进脚本。
import http from 'k6/http';

export const BASE = __ENV.BASE || 'http://app:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function must(res, what) {
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`${what} failed: HTTP ${res.status} ${res.body}`);
  }
  return res.json();
}

/**
 * 新建一个压测专用租户（接收限流放到很高，测的是系统本身而不是限流器），
 * 可选建一个模拟接收方并登记为端点。
 */
export function prepare({ endpoints = 1, failureRate = 0, latencyMs = 20, jitterMs = 20, deliverQps = 1000 } = {}) {
  const admin = { headers: { ...JSON_HEADERS, Authorization: `Bearer ${__ENV.ADMIN_TOKEN}` } };
  const tenant = must(http.post(`${BASE}/api/admin/tenants`,
    JSON.stringify({ name: `bench-${Date.now()}`, ingestQps: 10000 }), admin), 'create tenant');
  const auth = { headers: { ...JSON_HEADERS, Authorization: `Bearer ${tenant.apiKey}` } };
  let sinkId = null;
  for (let i = 0; i < endpoints; i++) {
    const sink = must(http.post(`${BASE}/api/v1/sinks`, JSON.stringify({
      name: `bench-${i}`, config: { failureRate, failureStatus: 503, latencyMs, jitterMs },
    }), auth), 'create sink');
    sinkId = sinkId || sink.id;
    must(http.post(`${BASE}/api/v1/endpoints`, JSON.stringify({
      url: sink.url, eventTypes: ['*'], maxAttempts: 8, deliverQps,
    }), auth), 'create endpoint');
  }
  return { apiKey: tenant.apiKey, tenantId: tenant.tenant.id, sinkId };
}

export function postEvent(data) {
  const key = `${__VU}-${__ITER}-${Date.now()}`;
  return http.post(`${BASE}/api/v1/events`,
    JSON.stringify({ type: 'order.created', payload: { orderId: key, amount: 99.5, items: [1, 2, 3] } }),
    { headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.apiKey}`, 'Idempotency-Key': key },
      tags: { name: 'POST /events' } });
}

export function summaryWithSetup(data) {
  return { '/out/summary.json': JSON.stringify({ metrics: data.metrics, setup_data: data.setup_data }, null, 2) };
}
