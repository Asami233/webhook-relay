// 投递场景：模拟接收方按 FAILURE_RATE 失败、LATENCY_MS 延迟；以 RATE 次/秒发事件 DURATION。
// 投递延迟与排空时间由 bench/run.sh 在压测结束后从 /actuator/prometheus 与模拟接收方统计读取。
import { check } from 'k6';
import { prepare, postEvent, summaryWithSetup } from './lib.js';

const RATE = Number(__ENV.RATE || 50);

export const options = {
  scenarios: {
    events: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(RATE, 100), maxVUs: 300,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // 声明阈值才会在汇总里单独输出该子指标（只含事件请求，不含 setup）
  thresholds: { 'http_req_duration{name:POST /events}': ['p(99)<5000'] },
};

export function setup() {
  return prepare({
    endpoints: 1,
    failureRate: Number(__ENV.FAILURE_RATE || 0),
    latencyMs: Number(__ENV.LATENCY_MS || 50),
    jitterMs: Number(__ENV.JITTER_MS || 50),
    deliverQps: Number(__ENV.DELIVER_QPS || 1000),
  });
}

export default function (data) {
  check(postEvent(data), { 'accepted 202': (r) => r.status === 202 });
}

export const handleSummary = summaryWithSetup;
