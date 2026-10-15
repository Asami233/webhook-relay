-- 令牌桶（原子执行）。KEYS[1] 桶；ARGV[1] 每秒补充速率；ARGV[2] 桶容量（允许的突发）；ARGV[3] 本次需要的令牌数；
-- ARGV[4] 可预约的最长等待毫秒数（0 = 不预约，令牌不足直接拒绝）。
-- 时间取 Redis 服务器的 TIME，而不是各应用实例的时钟：多实例共享一个桶时不受时钟偏差影响。
-- 返回 {是否放行(1/0), 毫秒数}：放行时为调用方需要先等待的时间（预约），拒绝时为建议的重试间隔。
--
-- 预约：令牌不足但等待不超过上限时，直接扣减（令牌数可为负）并告诉调用方等多久。后来者看到的欠账更多、
-- 等待更长，于是自然排队、各睡一次就能发出；不预约的话，所有等待者同时醒来再抢，只有一个成功（惊群）。
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local maxReserveMs = tonumber(ARGV[4] or '0')

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate / 1000)

local allowed = 0
local wait = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  wait = math.ceil((requested - tokens) * 1000 / rate)
  if wait <= maxReserveMs then
    tokens = tokens - requested
    allowed = 1
  end
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- 桶装满所需时间的两倍后过期：空闲租户不常驻内存，过期后重建即为满桶，语义不变
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) * 2 + 1000)
return {allowed, wait}
