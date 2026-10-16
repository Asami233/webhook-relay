-- 控制台成功率图表按时间窗口统计尝试（最近 60 分钟，按分钟聚合）
CREATE INDEX idx_attempt_started ON delivery_attempt (started_at);
