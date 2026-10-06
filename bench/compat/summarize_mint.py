#!/usr/bin/env python3
"""summarize_mint.py OUT_DIR: per-suite PASS/FAIL/NA counts and the most frequent failing test functions from mint's log.json."""
import collections, json, os, sys

out = sys.argv[1]
total = collections.Counter()
rows = []
fails = collections.Counter()
detail = {}
for suite in sorted(os.listdir(out)):
    p = os.path.join(out, suite, "log.json")
    if not os.path.isfile(p):
        continue
    c = collections.Counter()
    for line in open(p, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            e = json.loads(line)
        except ValueError:
            continue
        st = e.get("status", "?")
        c[st] += 1
        if st == "FAIL":
            key = f"{suite}: {e.get('function', e.get('name', '?'))}"
            fails[key] += 1
            detail.setdefault(key, (e.get("error") or e.get("message") or "")[:160].replace("\n", " "))
    rc = open(os.path.join(out, suite, "exit-code")).read().strip() if os.path.exists(os.path.join(out, suite, "exit-code")) else "?"
    rows.append((suite, c, rc))
    total.update(c)
print(f"{'suite':18} {'PASS':>5} {'FAIL':>5} {'NA':>4}  exit")
for suite, c, rc in rows:
    print(f"{suite:18} {c['PASS']:5} {c['FAIL']:5} {c['NA']:4}  {rc}")
print(f"{'TOTAL':18} {total['PASS']:5} {total['FAIL']:5} {total['NA']:4}")
print("top failing functions:")
for k, n in fails.most_common(25):
    print(f"  {n:3} {k}  | {detail[k]}")
json.dump({"suites": {s: dict(c) for s, c, _ in rows}, "total": dict(total), "failing": fails.most_common()}, open(os.path.join(out, "summary.json"), "w"), indent=1)
