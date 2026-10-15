"""把 target/bench/results.jsonl 打印成对比表：python -I bench/show.py [label 前缀 ...]"""
import json
import sys

prefixes = sys.argv[1:]
print(f"{'label':22} {'rps':>6} {'ok%':>5} {'p50':>7} {'p99':>7} {'drop':>6} {'acq ms':>7} {'use ms':>6} {'app cpu':>7} {'app MiB':>7} {'db MiB':>6} {'e2e p50':>7} {'e2e p99':>7} {'deferred':>8} {'gc s':>5}")
for line in open("target/bench/results.jsonl", encoding="utf-8"):
    d = json.loads(line)
    if prefixes and not any(d["label"].startswith(p) for p in prefixes):
        continue
    i, db, c, dl = d["ingest"], d.get("db") or {}, d["containers"], d["delivery"]
    print(f"{d['label']:22} {i['achieved_rps']:>6} {round(i['ok_ratio']*100):>5} {i['p50_ms']:>7} {i['p99_ms']:>7} {i['dropped_iterations']:>6} "
          f"{db.get('acquire_avg_ms', '-'):>7} {db.get('usage_avg_ms', '-'):>6} {c.get('app', {}).get('cpu_avg', '-'):>7} "
          f"{c.get('app', {}).get('mem_max_mib', '-'):>7} {c.get('mysql', {}).get('mem_max_mib', '-'):>6} "
          f"{str(dl.get('e2e_p50_s')):>7} {str(dl.get('e2e_p99_s')):>7} {sum(dl['deferred'].values()):>8} {(d.get('jvm') or {}).get('gc_pause_s', '-'):>5}")
