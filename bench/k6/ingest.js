// 固定到达速率压接收接口：RATE 次/秒，持续 DURATION。ENDPOINTS=0 只测写库，=1 含扇出一条投递（投递同时在跑）。
import { check } from 'k6';
import { prepare, postEvent, summaryWithSetup } from './lib.js';

const RATE = Number(__ENV.RATE || 100);

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(RATE, 200), maxVUs: 600,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // 声明阈值才会在汇总里单独输出该子指标（只含事件请求，不含 setup）
  thresholds: { 'http_req_duration{name:POST /events}': ['p(99)<5000'] },
};

export function setup() {
  return prepare({ endpoints: Number(__ENV.ENDPOINTS || 0), latencyMs: 20, jitterMs: 20 });
}

export default function (data) {
  const res = postEvent(data);
  check(res, { 'accepted 202': (r) => r.status === 202 });
}

export const handleSummary = summaryWithSetup;
