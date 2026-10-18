#!/usr/bin/env python3
"""Sampler and drift report for soak.sh. Standard library only.

  soak_sample.py sample CSV SECONDS INTERVAL [--url URL] [--container NAME] [--t0 EPOCH]
      scrape the server's Prometheus endpoint every INTERVAL seconds for SECONDS and append one CSV row per scrape.
      Rates and p99 are computed from the counter / histogram-bucket deltas between two consecutive scrapes (what
      rate() and histogram_quantile() would give in Prometheus), so no Prometheus server is needed.
      --container: also reads the resident set (VmRSS of PID 1) and the real disk usage (du) through `docker exec`.
  soak_sample.py report CSV
      first-window vs last-window throughput and p99 and a warning for every series that only grows.
"""
import argparse, csv, math, os, re, subprocess, sys, time, urllib.request
from datetime import datetime, timezone

COLS = ["ts_utc", "elapsed_s", "up", "rss_mb", "heap_used_mb", "nonheap_mb", "open_fds", "threads", "meta_file_mb", "meta_pages",
        "meta_free_pages", "gc_queue", "disk_free_gb", "store_du_mb", "rps", "p99_put_ms", "p99_get_ms", "err_5xx_pct", "gc_pause_ms_per_s"]
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)')
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def scrape(url):
    out = []
    with urllib.request.urlopen(url, timeout=20) as r:
        for line in r.read().decode().splitlines():
            m = LINE.match(line)
            if m and not line.startswith("#"):
                out.append((m.group(1), dict(LABEL.findall(m.group(2) or "")), float(m.group(3))))
    return out


def total(samples, name, **want):
    return sum(v for n, l, v in samples if n == name and all(l.get(k) == w for k, w in want.items()) and not math.isnan(v))


def buckets(samples, op):
    b = {}
    for n, l, v in samples:
        if n == "sectoriadb_s3_requests_seconds_bucket" and l.get("operation") == op:
            le = math.inf if l["le"] == "+Inf" else float(l["le"])
            b[le] = b.get(le, 0.0) + v
    return b


def quantile(q, now, prev):
    """histogram_quantile over the increase between two scrapes; None without observations."""
    if not now:
        return None
    prev = prev or {}   # no observation yet at the previous scrape: every bucket started at 0
    les = sorted(now)
    d = [(le, now[le] - prev.get(le, 0.0)) for le in les]
    n = d[-1][1]
    if n <= 0:
        return None
    rank, lo, lo_c = q * n, 0.0, 0.0
    for le, c in d:
        if c >= rank:
            if le == math.inf:
                return lo
            return lo + (le - lo) * ((rank - lo_c) / (c - lo_c) if c > lo_c else 1.0)
        lo, lo_c = le, c
    return lo


def docker_exec(container, cmd):
    try:
        return subprocess.run(["docker", "exec", container, "sh", "-c", cmd], capture_output=True, text=True, timeout=60).stdout
    except Exception:
        return ""


def sample(a):
    new = not os.path.exists(a.csv) or os.path.getsize(a.csv) == 0
    f = open(a.csv, "a", newline="")
    w = csv.writer(f)
    if new:
        w.writerow(COLS)
    start = time.time()
    t0 = a.t0 or start  # elapsed_s counts from t0 (soak.sh passes its own start so the final row continues the series)
    prev = None
    while True:
        now_t = time.time()
        row = {"ts_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "elapsed_s": round(now_t - t0), "up": 0}
        try:
            s = scrape(a.url)
            row["up"] = 1
            row["heap_used_mb"] = round(total(s, "jvm_memory_used_bytes", area="heap") / 1048576, 1)
            row["nonheap_mb"] = round(total(s, "jvm_memory_used_bytes", area="nonheap") / 1048576, 1)
            row["open_fds"] = int(total(s, "process_files_open_files"))
            row["threads"] = int(total(s, "jvm_threads_live_threads"))
            row["meta_file_mb"] = round(total(s, "sectoriadb_metastore_file_bytes") / 1048576, 2)
            row["meta_pages"] = int(total(s, "sectoriadb_metastore_pages"))
            row["meta_free_pages"] = int(total(s, "sectoriadb_metastore_free_pages"))
            row["gc_queue"] = int(total(s, "sectoriadb_chunks_gc_queue_length"))
            row["disk_free_gb"] = round(total(s, "sectoriadb_disk_free_bytes", dir="data") / 2**30, 2)
            cur = {"count": total(s, "sectoriadb_s3_requests_seconds_count"),
                   "err": total(s, "sectoriadb_s3_requests_seconds_count", status_class="5xx"),
                   "gc": total(s, "jvm_gc_pause_seconds_sum"), "put": buckets(s, "PutObject"), "get": buckets(s, "GetObject"), "t": now_t}
            if prev and cur["count"] >= prev["count"]:  # a restart resets the counters: skip that interval
                dt = cur["t"] - prev["t"]
                dn = cur["count"] - prev["count"]
                row["rps"] = round(dn / dt, 1)
                row["err_5xx_pct"] = round(100 * (cur["err"] - prev["err"]) / dn, 3) if dn else 0
                row["gc_pause_ms_per_s"] = round(1000 * (cur["gc"] - prev["gc"]) / dt, 2)
                for k, col in (("put", "p99_put_ms"), ("get", "p99_get_ms")):
                    p = quantile(0.99, cur[k], prev[k])
                    row[col] = None if p is None else round(p * 1000, 1)
            prev = cur
        except Exception as e:  # server down / scrape failure: the row says up=0
            print(f"scrape failed: {e}", file=sys.stderr)
            prev = None
        if a.container:
            m = re.search(r"VmRSS:\s+(\d+) kB", docker_exec(a.container, "grep VmRSS /proc/1/status"))
            row["rss_mb"] = round(int(m.group(1)) / 1024, 1) if m else None
            du = docker_exec(a.container, "du -sk /data 2>/dev/null | cut -f1").strip()
            row["store_du_mb"] = round(int(du) / 1024, 1) if du.isdigit() else None
        w.writerow([row.get(c, "") if row.get(c) is not None else "" for c in COLS])
        f.flush()
        if now_t - start + a.interval > a.seconds:
            return
        time.sleep(max(0.0, start + (round((now_t - start) / a.interval) + 1) * a.interval - time.time()))


def kendall(xs):
    n = len(xs)
    s = sum((xs[j] > xs[i]) - (xs[j] < xs[i]) for i in range(n) for j in range(i + 1, n))
    return s / (n * (n - 1) / 2) if n > 1 else 0.0


def mean(v):
    return sum(v) / len(v) if v else None


def report(a):
    rows = list(csv.DictReader(open(a.csv)))
    if len(rows) < 4:
        print("too few samples for a drift report"); return 0
    num = lambda r, k: float(r[k]) if r.get(k) not in (None, "") else None
    total_s = float(rows[-1]["elapsed_s"])
    w = min(3600.0, total_s / 4)
    warm = min(600.0, total_s / 10)
    first = [r for r in rows if warm <= float(r["elapsed_s"]) <= warm + w]
    last = [r for r in rows if float(r["elapsed_s"]) >= total_s - w]
    print(f"== soak drift: {len(rows)} samples over {total_s / 3600:.2f} h; first window = {warm / 60:.0f}..{(warm + w) / 60:.0f} min, "
          f"last window = last {w / 60:.0f} min")
    rc = 0
    for col, label, worse in (("rps", "throughput (req/s)", lambda a, b: b < a * 0.9), ("p99_put_ms", "p99 PutObject (ms)", lambda a, b: b > a * 1.25),
                              ("p99_get_ms", "p99 GetObject (ms)", lambda a, b: b > a * 1.25)):
        a1 = mean([x for x in (num(r, col) for r in first) if x is not None])
        b1 = mean([x for x in (num(r, col) for r in last) if x is not None])
        if a1 is None or b1 is None:
            print(f"  {label:22s} no data"); continue
        flag = "  <-- WARN: drift" if worse(a1, b1) else ""
        rc |= bool(flag)
        print(f"  {label:22s} first {a1:10.1f}   last {b1:10.1f}   {100 * (b1 - a1) / a1 if a1 else 0:+6.1f} %{flag}")
    up = [r for r in rows if r["up"] == "1"]
    if len(up) < len(rows):
        print(f"  WARN: {len(rows) - len(up)} of {len(rows)} scrapes failed (server down or unreachable)"); rc = 1
    errs = [x for x in (num(r, "err_5xx_pct") for r in rows) if x]
    print(f"  5xx: {'none in any interval' if not errs else f'{len(errs)} intervals with errors, max {max(errs):.3f} %'}")
    print("  growth after warm-up (first third vs last third of the samples; Kendall tau = 1 means strictly rising):")
    body = [r for r in rows if float(r["elapsed_s"]) >= warm]
    for col in ("rss_mb", "heap_used_mb", "nonheap_mb", "open_fds", "threads", "meta_file_mb", "meta_pages", "meta_free_pages", "gc_queue",
                "store_du_mb", "gc_pause_ms_per_s"):
        xs = [x for x in (num(r, col) for r in body) if x is not None]
        if len(xs) < 6:
            continue
        t = len(xs) // 3
        a1, b1, tau = mean(xs[:t]), mean(xs[-t:]), kendall(xs)
        grows = b1 > a1 * 1.10 + 1e-9 and tau >= 0.6
        expected = col in ("meta_file_mb", "meta_pages", "store_du_mb")  # these follow the data set, not a leak by themselves
        flag = ("  <-- WARN: monotonic growth" + (" (follows the data set, check against the object count)" if expected else "")) if grows else ""
        rc |= bool(grows and not expected)
        print(f"    {col:18s} {a1:10.1f} -> {b1:10.1f}   tau={tau:+.2f}{flag}")
    return rc


ap = argparse.ArgumentParser()
sub = ap.add_subparsers(dest="cmd", required=True)
s = sub.add_parser("sample"); s.add_argument("csv"); s.add_argument("seconds", type=float); s.add_argument("interval", type=float)
s.add_argument("--t0", type=float, default=0.0); s.add_argument("--url", default="http://localhost:9464/actuator/prometheus"); s.add_argument("--container", default="")
r = sub.add_parser("report"); r.add_argument("csv")
args = ap.parse_args()
if args.cmd == "sample":
    sample(args)
else:
    sys.exit(report(args))
