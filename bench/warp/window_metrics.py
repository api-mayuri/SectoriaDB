#!/usr/bin/env python3
"""Server-side latency and batching figures for the analysis window of one warp run (stage 09 comparisons).

    python3 -I window_metrics.py bench/results/<run> [--prom http://localhost:9090]

The window is taken from analyze.txt like validate_metrics.py does ("starting HH:MM:SS UTC" + "Ran Duration"). For every
histogram below it prints p50 / p99 (histogram_quantile over the rate in the window) and the sample count; for the commit
rate it prints commits per second. Values are Prometheus-interpolated (bucket resolution), not exact.
"""
import argparse, datetime as dt, json, os, re, sys, urllib.parse, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("run_dir")
ap.add_argument("--prom", default="http://localhost:9090")
ap.add_argument("--json", action="store_true", help="print one JSON object instead of a table")
a = ap.parse_args()

meta = json.load(open(os.path.join(a.run_dir, "meta.json")))
day = meta["started_utc"][:10]
text = open(os.path.join(a.run_dir, "analyze.txt"), encoding="utf-8").read()
m = re.search(r"Report: \w+(?: \(\d+ reqs\))?\. Ran Duration: (?:(\d+)h)?(?:(\d+)m)?(\d+)s, starting (\d\d:\d\d:\d\d) UTC", text)
if not m:
    sys.exit("no report block in analyze.txt")
dur = int(m.group(1) or 0) * 3600 + int(m.group(2) or 0) * 60 + int(m.group(3))
t0 = dt.datetime.strptime(f"{day} {m.group(4)}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=dt.timezone.utc)
t_end = t0.timestamp() + dur
w = f"{dur}s"


def q(expr):
    url = a.prom + "/api/v1/query?" + urllib.parse.urlencode({"query": expr, "time": f"{t_end:.3f}"})
    r = json.load(urllib.request.urlopen(url, timeout=20))["data"]["result"]
    return float(r[0]["value"][1]) if r else None


def hist(name, labels=""):
    sel = f"{name}_bucket{{{labels}}}" if labels else f"{name}_bucket"
    cnt = f"{name}_count{{{labels}}}" if labels else f"{name}_count"
    return {
        "p50": q(f"histogram_quantile(0.5, sum by (le) (rate({sel}[{w}])))"),
        "p99": q(f"histogram_quantile(0.99, sum by (le) (rate({sel}[{w}])))"),
        "n": q(f"sum(increase({cnt}[{w}]))"),
    }


out = {
    "window_s": dur,
    "start_utc": m.group(4),
    "put_object_s": hist("sectoriadb_s3_requests_seconds", 'operation="PutObject"'),
    "small_append_lock_wait_s": hist("sectoriadb_small_append_lock_wait_seconds"),
    "small_durability_wait_s": hist("sectoriadb_small_durability_wait_seconds"),
    "small_group_fsync_records": hist("sectoriadb_small_group_fsync_records"),
    "meta_group_queue_wait_s": hist("sectoriadb_metastore_group_queue_wait_seconds"),
    "meta_group_batch_size": hist("sectoriadb_metastore_group_batch_size"),
    "meta_commit_s": hist("sectoriadb_metastore_commit_seconds"),
    "cuckoo_lock_wait_s": hist("sectoriadb_cuckoo_lock_wait_seconds"),
    "small_fsync_s": hist("sectoriadb_storage_fsync_seconds", 'target="small"'),
    "commits_per_s": (q(f"sum(increase(sectoriadb_metastore_commit_seconds_count[{w}]))") or 0) / dur,
    "small_fsyncs_per_s": (q(f'sum(increase(sectoriadb_storage_fsync_seconds_count{{target="small"}}[{w}]))') or 0) / dur,
    "cuckoo_fsyncs_per_s": (q(f'sum(increase(sectoriadb_storage_fsync_seconds_count{{target="cuckoo"}}[{w}]))') or 0) / dur,
}
if a.json:
    print(json.dumps(out))
else:
    for k, v in out.items():
        if isinstance(v, dict):
            f = lambda x: "-" if x is None else (f"{x*1000:.1f} ms" if k.endswith("_s") else f"{x:.2f}")
            n = "-" if v["n"] is None else f"{v['n']:.0f}"
            print(f"{k:28s} p50={f(v['p50']):>10s}  p99={f(v['p99']):>10s}  n={n}")
        else:
            print(f"{k:28s} {v}")
