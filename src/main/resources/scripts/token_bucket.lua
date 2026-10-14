-- 令牌桶（原子执行）。KEYS[1] 桶；ARGV[1] 每秒补充速率；ARGV[2] 桶容量（允许的突发）；ARGV[3] 本次需要的令牌数。
-- 时间取 Redis 服务器的 TIME，而不是各应用实例的时钟：多实例共享一个桶时不受时钟偏差影响。
-- 返回 {是否放行(1/0), 需要等待的毫秒数}。
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

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
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- 桶装满所需时间的两倍后过期：空闲租户不常驻内存，过期后重建即为满桶，语义不变
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) * 2 + 1000)
return {allowed, wait}
