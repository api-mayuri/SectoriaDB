#!/usr/bin/env python3
"""Smoke test of the provisioned Grafana dashboards: loads every dashboard through the HTTP API and runs the query of
every panel target through /api/ds/query (template variables are replaced by 'all'). Prints OK / EMPTY / ERROR per
panel and exits non-zero on ERROR (EMPTY is expected for panels whose events did not happen, e.g. quarantined slots).

    GRAFANA_URL=http://localhost:3000 GRAFANA_USER=admin GRAFANA_PASSWORD=... python3 -I check_dashboards.py [uid ...]
"""
import base64, json, os, re, sys, time, urllib.request, urllib.error

URL = os.environ.get("GRAFANA_URL", "http://localhost:3000").rstrip("/")
AUTH = base64.b64encode(f'{os.environ.get("GRAFANA_USER", "admin")}:{os.environ.get("GRAFANA_PASSWORD", "")}'.encode()).decode()


def api(path, body=None):
    req = urllib.request.Request(URL + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Basic " + AUTH, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, "_body": e.read().decode(errors="replace")[:500]}


def interpolate(s):
    for a, b in (("$__rate_interval", "1m"), ("$__interval", "1m"), ("${op:regex}", ".+"), ("${warp_id:regex}", ".+"),
                 ("$operation", ".+"), ("${operation}", ".+")):
        s = s.replace(a, b)
    return s


def main():
    uids = sys.argv[1:] or [d["uid"] for d in api("/api/search?type=dash-db")]
    now = int(time.time() * 1000)
    bad = 0
    for uid in uids:
        dash = api(f"/api/dashboards/uid/{uid}")
        if "dashboard" not in dash:
            print(f"ERROR cannot load dashboard {uid}: {dash}")
            bad += 1
            continue
        dash = dash["dashboard"]
        print(f"== {dash['title']} ({uid}): {len(dash['panels'])} panels, variables: {[v['name'] for v in dash.get('templating', {}).get('list', [])]}")
        for p in dash["panels"]:
            if p["type"] == "row":
                continue
            queries = []
            for t in p.get("targets", []):
                q = dict(t)
                for k in ("expr", "query"):
                    if k in q:
                        q[k] = interpolate(q[k])
                q.update({"datasource": t.get("datasource", p.get("datasource")), "intervalMs": 15000, "maxDataPoints": 500})
                queries.append(q)
            res = api("/api/ds/query", {"queries": queries, "from": str(now - 3600_000), "to": str(now)})
            status, detail = "OK", ""
            if "_http_error" in res:
                status, detail = "ERROR", f"http {res['_http_error']} {res['_body']}"
            else:
                series = 0
                for ref, r in res.get("results", {}).items():
                    if r.get("error"):
                        status, detail = "ERROR", f"{ref}: {r['error'][:300]}"
                    for fr in r.get("frames", []):
                        vals = fr.get("data", {}).get("values", [])
                        if vals and len(vals[0]) > 0:
                            series += 1
                if status == "OK" and series == 0:
                    status = "EMPTY"
                detail = detail or f"{series} series"
            if status == "ERROR":
                bad += 1
            print(f"  {status:5} {p['title'][:60]:60} {detail}")
    sys.exit(1 if bad else 0)


main()
