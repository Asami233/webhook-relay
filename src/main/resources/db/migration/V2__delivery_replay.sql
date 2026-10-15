-- 死信人工重放：attempts 继续累加（delivery_attempt 的 attempt_no 保持唯一、历史完整），
-- 本轮已用次数 = attempts - attempts_base，与端点 max_attempts 比较。
ALTER TABLE delivery
    ADD COLUMN attempts_base INT NOT NULL DEFAULT 0 COMMENT '最近一次重放时的 attempts',
    ADD COLUMN replay_count  INT NOT NULL DEFAULT 0 COMMENT '人工重放次数';

-- 控制台按状态列出（如死信列表）并键集分页
CREATE INDEX idx_delivery_tenant_status ON delivery (tenant_id, status, id);
