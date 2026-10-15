"""压测结果整理（仅标准库）。

  python -I bench/report.py drain <outdir> [timeout_seconds]   等压测租户的投递排空，打印耗时（秒）
  python -I bench/report.py summarize <outdir> <label> <drain_seconds>   输出一行 JSON 结果

<outdir> 由 bench/run.sh 生成：before.txt / after.txt（/actuator/prometheus 快照）、summary.json（k6）、stats.csv（docker stats 采样）。
分位数由直方图桶的差值按 Prometheus histogram_quantile 的线性插值计算。
"""
import json
import re
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8080"
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)')
LABEL = re.compile(r'(\w+)="([^"]*)"')


def parse(path):
    samples = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = LINE.match(line)
            if not m or line.startswith("#"):
                continue
            labels = tuple(sorted((k, v) for k, v in LABEL.findall(m.group(2) or "") if k != "application"))
            try:
                samples[(m.group(1), labels)] = float(m.group(3))
            except ValueError:
                pass
    return samples


def delta(before, after, name, **match):
    total = 0.0
    for (n, labels), v in after.items():
        if n != name or any(dict(labels).get(k) != val for k, val in match.items()):
            continue
        total += v - before.get((n, labels), 0.0)
    return total


def quantile(before, after, name, q, **match):
    buckets = {}
    for (n, labels), v in after.items():
        if n != name + "_bucket":
            continue
        d = dict(labels)
        if any(d.get(k) != val for k, val in match.items()):
            continue
        le = float("inf") if d["le"] == "+Inf" else float(d["le"])
        buckets[le] = buckets.get(le, 0.0) + v - before.get((n, labels), 0.0)
    if not buckets:
        return None
    bounds = sorted(buckets)
    total = buckets[bounds[-1]]
    if total <= 0:
        return None
    rank = q * total
    prev_bound, prev_count = 0.0, 0.0
    for b in bounds:
        count = buckets[b]
        if count >= rank:
            if b == float("inf"):
                return prev_bound
            if count == prev_count:
                return b
            return prev_bound + (b - prev_bound) * (rank - prev_count) / (count - prev_count)
        prev_bound, prev_count = b, count
    return bounds[-2] if len(bounds) > 1 else None


def db_pool(before, after):
    """连接池：平均等待获取连接的时间 vs 平均占用时间。等待远大于占用说明池子是瓶颈。"""
    n = delta(before, after, "hikaricp_connections_acquire_seconds_count")
    if n <= 0:
        return None
    return {
        "acquire_avg_ms": round(delta(before, after, "hikaricp_connections_acquire_seconds_sum") / n * 1000, 2),
        "usage_avg_ms": round(delta(before, after, "hikaricp_connections_usage_seconds_sum") / n * 1000, 2),
        "pool_max": after.get(("hikaricp_connections_max", (("pool", "HikariPool-1"),)), None),
    }


def jvm(before, after):
    gc_s = delta(before, after, "jvm_gc_pause_seconds_sum")
    return {
        "gc_pause_s": round(gc_s, 2),
        "gc_count": int(delta(before, after, "jvm_gc_pause_seconds_count")),
        "heap_max_mib": round(sum(v for (n, l), v in after.items()
                                  if n == "jvm_memory_max_bytes" and dict(l).get("area") == "heap" and v > 0) / 2**20),
        "nonheap_mib": round(sum(v for (n, l), v in after.items()
                                 if n == "jvm_memory_used_bytes" and dict(l).get("area") == "nonheap") / 2**20),
    }


def get(path, api_key):
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Bearer " + api_key})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def drain(outdir, timeout=300):
    setup = json.load(open(f"{outdir}/summary.json", encoding="utf-8"))["setup_data"]
    start = time.time()
    while time.time() - start < timeout:
        open_count = sum(len(get(f"/api/v1/deliveries?status={s}&limit=1", setup["apiKey"]))
                         for s in ("PENDING", "IN_FLIGHT"))
        if open_count == 0:
            return round(time.time() - start, 1)
        time.sleep(1)
    return None


def mem_mib(text):
    value = text.split("/")[0].strip()
    for unit, factor in (("GiB", 1024), ("MiB", 1), ("KiB", 1 / 1024)):
        if value.endswith(unit):
            return float(value[: -len(unit)]) * factor
    return None


def container_stats(outdir):
    result = {}
    try:
        lines = open(f"{outdir}/stats.csv", encoding="utf-8").read().splitlines()
    except FileNotFoundError:
        return result
    for line in lines:
        parts = line.split(",")
        if len(parts) != 3:
            continue
        name = parts[0].replace("webhook-relay-", "").replace("-1", "")
        cpu = float(parts[1].rstrip("%") or 0)
        mem = mem_mib(parts[2])
        s = result.setdefault(name, {"cpu_max": 0.0, "cpu_sum": 0.0, "n": 0, "mem_max_mib": 0.0})
        s["cpu_max"] = max(s["cpu_max"], cpu)
        s["cpu_sum"] += cpu
        s["n"] += 1
        if mem is not None:
            s["mem_max_mib"] = max(s["mem_max_mib"], mem)
    return {k: {"cpu_avg": round(v["cpu_sum"] / v["n"], 1), "cpu_max": round(v["cpu_max"], 1),
                "mem_max_mib": round(v["mem_max_mib"], 1)} for k, v in result.items() if v["n"]}


def read_config(outdir):
    try:
        return open(f"{outdir}/config.txt", encoding="utf-8").read().strip().splitlines()
    except FileNotFoundError:
        return None


def summarize(outdir, label, drain_seconds):
    before, after = parse(f"{outdir}/before.txt"), parse(f"{outdir}/after.txt")
    k6 = json.load(open(f"{outdir}/summary.json", encoding="utf-8"))
    m = {k: v.get("values", {}) for k, v in k6["metrics"].items()}
    # 只统计压测请求本身（不含 setup 里建租户/端点的几次请求）
    dur = m.get("http_req_duration{name:POST /events}") or m["http_req_duration"]
    checks = m.get("checks", {})
    ms = lambda v: round(v, 1) if v is not None else None
    sec = lambda v: round(v, 3) if v is not None else None
    sink = None
    setup = k6.get("setup_data") or {}
    if setup.get("sinkId"):
        sink = get(f"/api/v1/sinks/{setup['sinkId']}", setup["apiKey"])["stats"]
    row = {
        "label": label,
        "ingest": {
            "achieved_rps": round(m["iterations"]["rate"], 1),
            "requests": int(m["iterations"]["count"]),
            "ok_ratio": round(checks.get("passes", 0) / max(1, checks.get("passes", 0) + checks.get("fails", 0)), 4),
            "dropped_iterations": int(m.get("dropped_iterations", {}).get("count", 0)),
            "p50_ms": ms(dur.get("p(50)")), "p95_ms": ms(dur.get("p(95)")), "p99_ms": ms(dur.get("p(99)")),
            "max_ms": ms(dur.get("max")),
        },
        "delivery": {
            "succeeded": int(delta(before, after, "relay_delivery_total", result="succeeded")),
            "retried": int(delta(before, after, "relay_delivery_total", result="retried")),
            "dead": int(delta(before, after, "relay_delivery_total", result="dead")),
            "deferred": {r: int(delta(before, after, "relay_delivery_deferred_total", reason=r))
                         for r in ("rate_limited", "circuit_open", "endpoint_busy")},
            "e2e_p50_s": sec(quantile(before, after, "relay_delivery_latency_seconds", 0.5)),
            "e2e_p99_s": sec(quantile(before, after, "relay_delivery_latency_seconds", 0.99)),
            "attempt_p99_s": sec(quantile(before, after, "relay_delivery_attempt_seconds", 0.99, outcome="success")),
            "drain_seconds": drain_seconds,
        },
        "db": db_pool(before, after),
        "jvm": jvm(before, after),
        "sink": sink,
        "containers": container_stats(outdir),
        "config": read_config(outdir),
    }
    return row


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "drain":
        print(drain(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 300))
    elif cmd == "summarize":
        d = sys.argv[4]
        print(json.dumps(summarize(sys.argv[2], sys.argv[3], None if d == "None" else float(d)), ensure_ascii=False))
    else:
        sys.exit(__doc__)
