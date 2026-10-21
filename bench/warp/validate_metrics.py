#!/usr/bin/env python3
"""Cross-check of the server metrics against warp: for one result directory, compare what warp reports (requests and
bytes per operation in the measured window) with what Prometheus counted on the server side in the SAME window.

    python3 -I validate_metrics.py bench/results/<run> [--prom http://localhost:9090] [--tolerance 5]

Window: warp's own "starting HH:MM:SS UTC" + "Ran Duration" from analyze.txt. Server numbers are
increase(counter[window]) evaluated at the window end (Prometheus extrapolates to the window edges; with a 5 s
scrape interval this is accurate to about 1-2 % for windows of a few minutes; for windows under a minute allow more).
Exit code 1 if any compared figure differs by more than the tolerance (default 5 %).
"""
import argparse, datetime as dt, json, os, re, sys, urllib.parse, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("run_dir"); ap.add_argument("--prom", default="http://localhost:9090"); ap.add_argument("--tolerance", type=float, default=5.0)
ap.add_argument("--save", help="write the comparison table (markdown) to this file")
a = ap.parse_args()

meta = json.load(open(os.path.join(a.run_dir, "meta.json")))
day = meta["started_utc"][:10]
text = open(os.path.join(a.run_dir, "analyze.txt"), encoding="utf-8").read()

UNIT = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3}
# server operation(s) per warp op
OPS = {"GET": ["GetObject"], "PUT": ["PutObject", "UploadPart"], "DELETE": ["DeleteObject", "DeleteObjects"], "STAT": ["HeadObject"]}


def prom(q, t):
    url = a.prom + "/api/v1/query?" + urllib.parse.urlencode({"query": q, "time": f"{t:.3f}"})
    r = json.load(urllib.request.urlopen(url, timeout=20))
    return sum(float(x["value"][1]) for x in r["data"]["result"]) if r["data"]["result"] else 0.0


rows = []
bad = 0
# "Report: GET (4941 reqs). Ran Duration: 17s, starting 22:55:44 UTC"  (also "Report: GET. Concurrency: 20. Ran: 17s" in older output)
blocks = re.split(r"\n(?=Report: )", text)
for b in blocks:
    m = re.match(r"Report: (\w+)(?: \((\d+) reqs\))?\. Ran Duration: (?:(\d+)h)?(?:(\d+)m)?(\d+)s, starting (\d\d:\d\d:\d\d) UTC", b)
    if not m:
        continue
    op, reqs, start = m.group(1), m.group(2), m.group(6)
    dur = int(m.group(3) or 0) * 3600 + int(m.group(4) or 0) * 60 + int(m.group(5))
    if op not in OPS:
        continue
    t0 = dt.datetime.strptime(f"{day} {start}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=dt.timezone.utc)
    t_end = t0.timestamp() + dur
    # warp totals
    am = re.search(r"Average: ([\d.]+) (\w+)/s, ([\d.]+) obj/s", b)
    if am:
        mib_s = float(am.group(1)) * UNIT.get(am.group(2), 1) / 1048576
        obj_s = float(am.group(3))
    else:
        am2 = re.search(r"Average: ([\d.]+) obj/s", b)
        mib_s, obj_s = None, float(am2.group(1)) if am2 else None
    w = f"{dur}s"
    ops = "|".join(OPS[op])
    if op == "DELETE" and prom(f'sum(increase(sectoriadb_s3_requests_seconds_count{{operation="DeleteObjects"}}[{w}]))', t_end) > 0:
        print("DELETE: warp used batch DeleteObjects (one request deletes many keys); request counts are not comparable, skipped")
        continue
    srv_cnt = prom(f'sum(increase(sectoriadb_s3_requests_seconds_count{{operation=~"{ops}",status_class="2xx"}}[{w}]))', t_end)
    srv_ok_and_err = prom(f'sum(increase(sectoriadb_s3_requests_seconds_count{{operation=~"{ops}"}}[{w}]))', t_end)
    if op in ("PUT",):
        srv_bytes = prom(f'sum(increase(sectoriadb_s3_request_bytes_total{{operation=~"{ops}"}}[{w}]))', t_end)
    elif op == "GET":
        srv_bytes = prom(f'sum(increase(sectoriadb_s3_response_bytes_total{{operation=~"{ops}"}}[{w}]))', t_end)
    else:
        srv_bytes = None
    warp_cnt = obj_s * dur if obj_s is not None else None
    # NOT warp's "(N reqs)": that counts the whole run, while "Average ... obj/s (Ds)" and the window start cover the
    # analysed part only (warp drops the first and last seconds, where not all clients are running). Objects per
    # request is 1 for these modes, so obj/s x window is the number of requests in the window.
    warp_bytes = mib_s * 1048576 * dur if mib_s is not None else None
    for what, wv, sv in (("requests", warp_cnt, srv_cnt), ("bytes", warp_bytes, srv_bytes)):
        if wv is None or sv is None:
            continue
        diff = (sv - wv) / wv * 100 if wv else float("nan")
        flag = "OK" if abs(diff) <= a.tolerance else "DIFF"
        if flag == "DIFF": bad += 1
        rows.append((op, what, wv, sv, diff, flag, dur))
        print(f"{op:6} {what:9} warp={wv:16.0f} server={sv:16.0f} diff={diff:+6.2f}%  {flag}  (window {dur}s from {start} UTC; server 2xx+err requests: {srv_ok_and_err:.0f})")

if a.save:
    with open(a.save, "w", encoding="utf-8") as f:
        f.write("| Операция | Величина | warp | сервер (Prometheus) | Разница |\n|---|---|---:|---:|---:|\n")
        for op, what, wv, sv, diff, flag, dur in rows:
            f.write(f"| {op} | {what} | {wv:,.0f} | {sv:,.0f} | {diff:+.2f} % |\n".replace(",", " "))
sys.exit(1 if bad or not rows else 0)
