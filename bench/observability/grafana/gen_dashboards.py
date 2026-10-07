#!/usr/bin/env python3
"""Generates the provisioned Grafana dashboards (dashboards/*.json). The JSON is committed; edit this file and rerun:

    python3 -I gen_dashboards.py

Dashboards:
  sectoriadb-server.json  server metrics from Prometheus (RED, saturation, internals, capacity, host)
  warp-client.json        warp live client results from InfluxDB (Flux)
"""
import json, os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboards")
PROM = {"type": "prometheus", "uid": "prometheus"}
INFLUX = {"type": "influxdb", "uid": "influx-warp"}

_id = [0]


def nid():
    _id[0] += 1
    return _id[0]


class Dash:
    def __init__(self, uid, title, tags, variables, annotations=None, refresh="5s", time_from="now-30m"):
        self.d = {
            "uid": uid, "title": title, "tags": tags, "schemaVersion": 39, "version": 1, "editable": True,
            "graphTooltip": 1, "refresh": refresh, "time": {"from": time_from, "to": "now"},
            "timezone": "browser", "panels": [], "links": [],
            "templating": {"list": variables},
            "annotations": {"list": annotations or []},
        }
        self.y = 0
        self.x = 0
        self.rowh = 0

    def row(self, title):
        self._newline()
        self.d["panels"].append({"id": nid(), "type": "row", "title": title, "collapsed": False,
                                 "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": []})
        self.y += 1

    def _newline(self):
        if self.x:
            self.y += self.rowh
            self.x = 0
            self.rowh = 0

    def add(self, panel, w=8, h=8):
        if self.x + w > 24:
            self._newline()
        panel["id"] = nid()
        panel["gridPos"] = {"h": h, "w": w, "x": self.x, "y": self.y}
        self.d["panels"].append(panel)
        self.x += w
        self.rowh = max(self.rowh, h)
        if self.x >= 24:
            self._newline()

    def save(self, name):
        self._newline()
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            json.dump(self.d, f, indent=2, ensure_ascii=False)
            f.write("\n")


def pq(expr, legend="", ref="A", instant=False):
    t = {"datasource": PROM, "expr": expr, "legendFormat": legend, "refId": ref, "editorMode": "code",
         "range": not instant, "instant": instant}
    return t


def fq(flux, ref="A"):
    return {"datasource": INFLUX, "query": flux, "refId": ref}


def ts(title, targets, unit="short", desc="", ds=PROM, stack=False, minv=None, maxv=None, legend_calcs=None,
       fill=10, thresholds=None, log=False):
    custom = {"drawStyle": "line", "lineWidth": 1, "fillOpacity": fill, "showPoints": "never",
              "spanNulls": True, "stacking": {"mode": "normal" if stack else "none", "group": "A"}}
    if log:
        custom["scaleDistribution"] = {"type": "log", "log": 10}
    defaults = {"unit": unit, "custom": custom}
    if minv is not None:
        defaults["min"] = minv
    if maxv is not None:
        defaults["max"] = maxv
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        custom["thresholdsStyle"] = {"mode": "line"}
    return {"type": "timeseries", "title": title, "description": desc, "datasource": ds, "targets": targets,
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"legend": {"displayMode": "table" if legend_calcs else "list", "placement": "bottom",
                                   "calcs": legend_calcs or []},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


def stat(title, targets, unit="short", desc="", ds=PROM, thresholds=None, decimals=None, minv=None, maxv=None,
         color_mode="value"):
    d = {"unit": unit, "color": {"mode": "thresholds"},
         "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": "green", "value": None}]}}
    if decimals is not None:
        d["decimals"] = decimals
    if minv is not None:
        d["min"] = minv
    if maxv is not None:
        d["max"] = maxv
    return {"type": "stat", "title": title, "description": desc, "datasource": ds, "targets": targets,
            "fieldConfig": {"defaults": d, "overrides": []},
            "options": {"colorMode": color_mode, "graphMode": "area", "textMode": "auto",
                        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}}}


def table(title, targets, ds, desc="", unit_overrides=None):
    return {"type": "table", "title": title, "description": desc, "datasource": ds, "targets": targets,
            "fieldConfig": {"defaults": {}, "overrides": unit_overrides or []}, "options": {"showHeader": True}}


R = "$__rate_interval"
OP = 'operation=~"$operation"'
GREEN_YELLOW_RED = lambda y, r: [{"color": "green", "value": None}, {"color": "yellow", "value": y}, {"color": "red", "value": r}]
RED_AT = lambda r: [{"color": "green", "value": None}, {"color": "red", "value": r}]


def q_hist(metric, q, by="operation", sel="", rate=R):
    by_l = f"le, {by}" if by else "le"
    return f"histogram_quantile({q}, sum by ({by_l}) (rate({metric}_bucket{sel}[{rate}])))"


def server():
    var_op = {"name": "operation", "label": "operation", "type": "query", "datasource": PROM, "refresh": 2,
              "query": {"query": "label_values(sectoriadb_s3_requests_seconds_count, operation)", "refId": "q"},
              "includeAll": True, "multi": True, "allValue": ".+", "current": {"text": "All", "value": "$__all"},
              "sort": 1}
    ann = [
        {"name": "Annotations & Alerts", "builtIn": 1, "enable": True, "hide": True,
         "datasource": {"type": "grafana", "uid": "-- Grafana --"},
         "iconColor": "rgba(0, 211, 255, 1)", "type": "dashboard"},
        {"name": "warp runs", "enable": True, "iconColor": "orange",
         "datasource": {"type": "grafana", "uid": "-- Grafana --"},
         "target": {"type": "tags", "tags": ["warp"], "limit": 100, "matchAny": False}},
    ]
    d = Dash("sectoriadb-server", "SectoriaDB — server", ["sectoriadb", "prometheus"], [var_op], ann)

    # ---- overview ----
    d.row("Overview")
    d.add(stat("Requests/s", [pq(f"sum(rate(sectoriadb_s3_requests_seconds_count[{R}]))")], "reqps"), 4, 4)
    d.add(stat("Error ratio (4xx+5xx)",
               [pq(f'sum(rate(sectoriadb_s3_requests_seconds_count{{status_class=~"4xx|5xx"}}[{R}])) / sum(rate(sectoriadb_s3_requests_seconds_count[{R}]))')],
               "percentunit", thresholds=GREEN_YELLOW_RED(0.01, 0.05), decimals=2), 4, 4)
    d.add(stat("5xx/s", [pq(f'sum(rate(sectoriadb_s3_requests_seconds_count{{status_class="5xx"}}[{R}]))')], "reqps",
               thresholds=RED_AT(0.01), decimals=2), 4, 4)
    d.add(stat("p99 latency (all ops)", [pq(q_hist("sectoriadb_s3_requests_seconds", 0.99, by=""))], "s",
               thresholds=GREEN_YELLOW_RED(0.5, 2)), 4, 4)
    d.add(stat("In flight", [pq("sum(sectoriadb_s3_requests_inflight)")], "short"), 4, 4)
    d.add(stat("Cuckoo fill", [pq("100 * sum(sectoriadb_cuckoo_slots_active) / sum(sectoriadb_cuckoo_slots_capacity)")], "percent",
               thresholds=GREEN_YELLOW_RED(70, 90), decimals=1, minv=0, maxv=100), 4, 4)

    # ---- RED ----
    d.row("Rate / Errors / Duration (per operation)")
    d.add(ts("Requests/s by operation", [pq(f"sum by (operation) (rate(sectoriadb_s3_requests_seconds_count{{{OP}}}[{R}]))", "{{operation}}")],
             "reqps", legend_calcs=["mean", "max"], stack=True), 8, 8)
    d.add(ts("Throughput: bytes in/out", [
        pq(f"sum(rate(sectoriadb_s3_request_bytes_total[{R}]))", "in (request bodies)", "A"),
        pq(f"sum(rate(sectoriadb_s3_response_bytes_total[{R}]))", "out (response bodies)", "B")], "Bps",
        legend_calcs=["mean", "max"]), 8, 8)
    d.add(ts("In flight by operation", [pq(f"sum by (operation) (sectoriadb_s3_requests_inflight{{{OP}}})", "{{operation}}")], "short"), 8, 8)
    for q, name in ((0.5, "p50"), (0.95, "p95"), (0.99, "p99")):
        d.add(ts(f"Latency {name} by operation",
                 [pq(q_hist("sectoriadb_s3_requests_seconds", q, sel=f"{{{OP}}}"), "{{operation}}")], "s",
                 desc="Full request duration up to the last response byte. Percentiles from histogram buckets (Prometheus); "
                      "upper bucket bounds make values above the last bound (see docs, section 6) show as the bound.",
                 log=True), 8, 8)
    d.add(ts("Error ratio by status class",
             [pq(f'sum by (status_class) (rate(sectoriadb_s3_requests_seconds_count{{status_class=~"4xx|5xx"}}[{R}])) / ignoring(status_class) group_left sum(rate(sectoriadb_s3_requests_seconds_count[{R}]))',
                 "{{status_class}}")], "percentunit", minv=0), 8, 8)
    d.add(ts("Errors/s by S3 code and operation",
             [pq(f"sum by (code, operation) (rate(sectoriadb_s3_errors_total{{{OP}}}[{R}]))", "{{code}} / {{operation}}")], "ops",
             legend_calcs=["max"], stack=True), 8, 8)
    d.add(ts("Auth failures by reason",
             [pq(f"sum by (reason) (rate(sectoriadb_s3_auth_failures_total[{R}]))", "{{reason}}")], "ops", stack=True,
             desc="signature_mismatch, unknown_key, clock_skew, ... A burst of unknown_key / signature_mismatch is a probing client."), 8, 8)
    d.add(ts("GET time to first byte", [
        pq(q_hist("sectoriadb_s3_get_ttfb_seconds", 0.5, by=""), "p50", "A"),
        pq(q_hist("sectoriadb_s3_get_ttfb_seconds", 0.95, by=""), "p95", "B"),
        pq(q_hist("sectoriadb_s3_get_ttfb_seconds", 0.99, by=""), "p99", "C")], "s", log=True), 8, 8)
    d.add(ts("Slow and aborted requests per second", [
        pq(f"sum by (operation) (rate(sectoriadb_s3_requests_slow_total[{R}]))", "slow {{operation}}", "A"),
        pq(f"sum by (operation) (rate(sectoriadb_s3_requests_aborted_total[{R}]))", "aborted {{operation}}", "B")], "ops"), 8, 8)

    # ---- Saturation ----
    d.row("Saturation (USE)")
    d.add(ts("fsync p99 by target", [pq(q_hist("sectoriadb_storage_fsync_seconds", 0.99, by="target"), "{{target}}")], "s",
             desc="One FileChannel.force: chunk data, slot record, small-object append, metastore commit.", log=True), 8, 8)
    d.add(ts("Disk read latency p99 by target", [pq(q_hist("sectoriadb_storage_disk_read_seconds", 0.99, by="target"), "{{target}}")], "s", log=True), 8, 8)
    d.add(ts("Disk write latency p99 by target (page cache, before fsync)", [pq(q_hist("sectoriadb_storage_disk_write_seconds", 0.99, by="target"), "{{target}}")], "s", log=True), 8, 8)
    d.add(ts("Engine disk throughput by target", [
        pq(f"sum by (target) (rate(sectoriadb_storage_disk_read_bytes_total[{R}]))", "read {{target}}", "A"),
        pq(f"-sum by (target) (rate(sectoriadb_storage_disk_write_bytes_total[{R}]))", "write {{target}}", "B")], "Bps",
        desc="Writes are drawn below zero."), 8, 8)
    d.add(ts("Metastore: writer-lock wait and commit p99", [
        pq(q_hist("sectoriadb_metastore_writer_lock_wait_seconds", 0.99, by=""), "writer lock wait p99", "A"),
        pq(q_hist("sectoriadb_metastore_commit_seconds", 0.99, by=""), "commit p99", "B"),
        pq(q_hist("sectoriadb_metastore_commit_seconds", 0.5, by=""), "commit p50", "C")], "s", log=True,
        desc="Wait growing faster than commit time means the single writer is the bottleneck (queue of writers)."), 8, 8)
    d.add(ts("Metastore commits/s", [pq(f"sum(rate(sectoriadb_metastore_commit_seconds_count[{R}]))", "commits/s")], "ops",
             desc="Rate of the commit histogram count. The last_txid gauge is cached for 15 s and therefore steps; do not rate() it."), 8, 8)
    d.add(ts("Engine lock waits p99", [
        pq(q_hist("sectoriadb_small_append_lock_wait_seconds", 0.99, by=""), "small append", "A"),
        pq(q_hist("sectoriadb_cuckoo_lock_wait_seconds", 0.99, by=""), "cuckoo insert", "B")], "s", log=True), 8, 8)
    d.add(ts("Tomcat threads", [
        pq("tomcat_threads_busy_threads", "busy", "A"), pq("tomcat_threads_current_threads", "current", "B"),
        pq("tomcat_threads_config_max_threads", "max", "C")], "short", fill=0), 8, 8)
    d.add(ts("Tomcat connections", [
        pq("tomcat_connections_current_connections", "current", "A"),
        pq("tomcat_connections_keepalive_current_connections", "keep-alive", "B"),
        pq("tomcat_connections_config_max_connections", "max", "C")], "short", fill=0), 8, 8)
    d.add(ts("JVM GC pauses", [
        pq(q_hist("jvm_gc_pause_seconds", 0.99, by="gc"), "p99 {{gc}}", "A"),
        pq("max(jvm_gc_pause_seconds_max)", "max pause (scrape window)", "B"),
        pq(f"sum(rate(jvm_gc_pause_seconds_sum[{R}]))", "time in pauses (s/s)", "C")], "s", log=True), 8, 8)
    d.add(ts("JVM heap", [
        pq('sum(jvm_memory_used_bytes{area="heap"})', "heap used", "A"),
        pq('sum(jvm_memory_max_bytes{area="heap"})', "heap max", "B"),
        pq("jvm_gc_live_data_size_bytes", "live set after GC", "C")], "bytes", fill=0), 8, 8)
    d.add(ts("CPU", [
        pq("process_cpu_usage", "process", "A"), pq("system_cpu_usage", "system (container view)", "B")], "percentunit", minv=0, maxv=1), 8, 8)
    d.add(ts("Open file descriptors", [
        pq("process_files_open_files", "open", "A"), pq("process_files_max_files", "max", "B")], "short", fill=0), 8, 8)
    d.add(ts("JVM threads / allocation rate", [
        pq("jvm_threads_live_threads", "live threads", "A"),
        pq(f"rate(jvm_gc_memory_allocated_bytes_total[{R}])", "allocated B/s", "B")], "short"), 8, 8)

    # ---- Internals ----
    d.row("Engine internals")
    d.add(ts("Cuckoo eviction path length", [
        pq(q_hist("sectoriadb_cuckoo_eviction_path_length", 0.5, by=""), "p50", "A"),
        pq(q_hist("sectoriadb_cuckoo_eviction_path_length", 0.99, by=""), "p99", "B")], "short",
        desc="Moves per insert. 0 = a free slot was found directly; long paths mean a nearly full table."), 8, 8)
    d.add(ts("Inserts without eviction", [
        pq(f'sum(rate(sectoriadb_cuckoo_eviction_path_length_bucket{{le="0.5"}}[{R}])) / sum(rate(sectoriadb_cuckoo_eviction_path_length_count[{R}]))', "share of inserts with 0 moves")],
        "percentunit", minv=0, maxv=1), 8, 8)
    d.add(ts("Table full, rekeys, dedup hits", [
        pq(f"increase(sectoriadb_cuckoo_table_full_total[{R}])", "table full (inserts rejected)", "A"),
        pq(f"increase(sectoriadb_cuckoo_rekeys_total[{R}])", "rekeys (hash collisions)", "B"),
        pq(f"rate(sectoriadb_cuckoo_dedup_hits_total[{R}])", "dedup hits/s", "C")], "short"), 8, 8)
    d.add(ts("CRC / integrity failures (any > 0 is an incident)", [
        pq("sum by (kind) (increase(sectoriadb_integrity_crc_failures_total[$__interval]))", "{{kind}}")], "short", minv=0), 8, 8)
    d.add(ts("Quarantined slots", [pq("sectoriadb_cuckoo_slots_quarantined", "quarantined")], "short", minv=0), 8, 8)
    d.add(ts("GC queue (manifests waiting for garbage collection)", [pq("sectoriadb_gc_queue_length", "queue")], "short", minv=0), 8, 8)
    d.add(ts("Resize", [
        pq("increase(sectoriadb_resize_seconds_count[$__interval])", "resizes {{result}}", "A"),
        pq("sum(rate(sectoriadb_resize_seconds_sum[$__interval]))", "time resizing (s/s)", "B"),
        pq(f"increase(sectoriadb_auto_resize_runs_total[$__interval])", "auto-resize passes", "C")], "short"), 8, 8)
    d.add(ts("Cuckoo slots", [
        pq("sectoriadb_cuckoo_slots_active", "active", "A"), pq("sectoriadb_cuckoo_slots_capacity", "capacity", "B")], "short", fill=0), 8, 8)
    d.add(ts("Blobs", [pq("sectoriadb_blobs", "{{kind}}"), pq("sectoriadb_cuckoo_tables_loaded", "cuckoo loaded", "B"),
                       pq("sectoriadb_small_blobs_open", "small open", "C")], "short"), 8, 8)

    # ---- Capacity ----
    d.row("Capacity")
    d.add(ts("Cuckoo fill %", [pq("100 * sum(sectoriadb_cuckoo_slots_active) / sum(sectoriadb_cuckoo_slots_capacity)", "slots")], "percent", minv=0, maxv=100,
             thresholds=[{"color": "green", "value": None}, {"color": "yellow", "value": 70}, {"color": "red", "value": 90}],
             desc="Latency starts to grow at about 70 % fill; see the fill-level section of the benchmark docs."), 8, 8)
    d.add(ts("Disk free %", [pq("100 * sectoriadb_disk_free_bytes / sectoriadb_disk_total_bytes", "{{dir}}")], "percent", minv=0, maxv=100,
             thresholds=[{"color": "red", "value": None}, {"color": "yellow", "value": 10}, {"color": "green", "value": 20}]), 8, 8)
    d.add(ts("Disk full ETA (linear, 1h window)", [
        pq('(sectoriadb_disk_free_bytes{dir="data"} > 0) / clamp_min(-deriv(sectoriadb_disk_free_bytes{dir="data"}[15m]), 1) / 3600', "hours to full")], "h", minv=0,
        desc="Free bytes divided by the current consumption rate; empty when free space is not shrinking."), 8, 8)
    d.add(ts("Objects stored", [pq("sectoriadb_objects_stored", "objects")], "short", fill=0), 6, 8)
    d.add(ts("Bytes stored (logical) vs cuckoo used", [
        pq("sectoriadb_objects_stored_bytes", "objects (logical)", "A"),
        pq("sectoriadb_cuckoo_used_bytes", "cuckoo used", "B"),
        pq("sectoriadb_cuckoo_capacity_bytes", "cuckoo capacity", "C")], "bytes", fill=0), 6, 8)
    d.add(ts("Small objects: live / dead bytes", [
        pq("sectoriadb_small_live_bytes", "live", "A"), pq("sectoriadb_small_dead_bytes", "dead", "B")], "bytes", fill=0,
        desc="Dead bytes are returned by compaction (GC stage); a growing dead share costs disk."), 6, 8)
    d.add(ts("Metastore size", [
        pq("sectoriadb_metastore_file_bytes", "file bytes", "A"),
        pq("sectoriadb_metastore_free_pages * sectoriadb_metastore_page_size_bytes", "free pages (bytes)", "B")], "bytes", fill=0), 6, 8)
    d.add(ts("Metastore pages", [
        pq("sectoriadb_metastore_pages", "pages", "A"), pq("sectoriadb_metastore_free_pages", "free", "B"),
        pq("sectoriadb_metastore_live_readers", "live readers", "C")], "short", fill=0), 8, 8)

    # ---- Host ----
    d.row("Host (node_exporter)")
    dev = 'device=~"(vd|sd|nvme|xvd)[a-z0-9]*"'
    d.add(ts("CPU by mode", [pq(f'sum by (mode) (rate(node_cpu_seconds_total{{mode!="idle"}}[{R}])) / scalar(count(count by (cpu)(node_cpu_seconds_total)))', "{{mode}}")],
             "percentunit", stack=True, minv=0, maxv=1), 8, 8)
    d.add(ts("Disk IO utilisation", [pq(f"rate(node_disk_io_time_seconds_total{{{dev}}}[{R}])", "{{device}}")], "percentunit", minv=0, maxv=1.05,
             desc="Share of time the device had IO in flight (iostat %util). Near 100 % = saturated."), 8, 8)
    d.add(ts("Disk latency (await)", [
        pq(f"rate(node_disk_read_time_seconds_total{{{dev}}}[{R}]) / clamp_min(rate(node_disk_reads_completed_total{{{dev}}}[{R}]), 1)", "read {{device}}", "A"),
        pq(f"rate(node_disk_write_time_seconds_total{{{dev}}}[{R}]) / clamp_min(rate(node_disk_writes_completed_total{{{dev}}}[{R}]), 1)", "write {{device}}", "B"),
        pq(f"rate(node_disk_flush_requests_time_seconds_total{{{dev}}}[{R}]) / clamp_min(rate(node_disk_flush_requests_total{{{dev}}}[{R}]), 1)", "flush {{device}}", "C")], "s"), 8, 8)
    d.add(ts("Disk throughput", [
        pq(f"rate(node_disk_read_bytes_total{{{dev}}}[{R}])", "read {{device}}", "A"),
        pq(f"-rate(node_disk_written_bytes_total{{{dev}}}[{R}])", "write {{device}}", "B")], "Bps"), 8, 8)
    d.add(ts("Disk IOPS", [
        pq(f"rate(node_disk_reads_completed_total{{{dev}}}[{R}])", "read {{device}}", "A"),
        pq(f"-rate(node_disk_writes_completed_total{{{dev}}}[{R}])", "write {{device}}", "B")], "iops"), 8, 8)
    d.add(ts("Network", [
        pq(f'sum(rate(node_network_receive_bytes_total{{device!~"lo|veth.*|docker.*|br-.*"}}[{R}]))', "rx", "A"),
        pq(f'-sum(rate(node_network_transmit_bytes_total{{device!~"lo|veth.*|docker.*|br-.*"}}[{R}]))', "tx", "B")], "Bps"), 8, 8)
    d.add(ts("Memory and load", [
        pq("node_memory_MemAvailable_bytes", "available", "A"), pq("node_memory_Cached_bytes", "page cache", "B")], "bytes", fill=0), 8, 8)
    d.add(ts("Load average", [pq("node_load1", "load1", "A"), pq("node_load5", "load5", "B"),
                              pq("count(count by (cpu)(node_cpu_seconds_total))", "cpus", "C")], "short", fill=0), 8, 8)
    d.save("sectoriadb-server.json")


# ------------------------------------------------------------------------------------------------------------
def warp():
    base = ('from(bucket: v.defaultBucket)\n'
            '  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            '  |> filter(fn: (r) => r._measurement == "warp")\n'
            '  |> filter(fn: (r) => r.op =~ /^${op:regex}$/ and r.warp_id =~ /^${warp_id:regex}$/)\n')

    def rate_of(field, scale=""):
        return (base + f'  |> filter(fn: (r) => r._field == "{field}")\n'
                '  |> group(columns: ["op", "warp_id", "endpoint"])\n'
                '  |> derivative(unit: 1s, nonNegative: true)\n'
                + (f'  |> map(fn: (r) => ({{r with _value: r._value {scale}}}))\n' if scale else '')
                + '  |> aggregateWindow(every: v.windowPeriod, fn: mean, createEmpty: false)\n'
                '  |> group(columns: ["op", "warp_id", "endpoint"])\n'
                '  |> yield(name: "rate")')

    mean_lat = (base + '  |> filter(fn: (r) => r._field == "request_total_secs" or r._field == "requests")\n'
                '  |> group(columns: ["op", "warp_id", "endpoint", "_field"])\n'
                '  |> difference(nonNegative: true)\n'
                '  |> toFloat()\n'
                '  |> group(columns: ["op", "warp_id", "endpoint"])\n'
                '  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")\n'
                '  |> map(fn: (r) => ({_time: r._time, op: r.op, warp_id: r.warp_id, endpoint: r.endpoint,\n'
                '       _value: if r.requests > 0 then r.request_total_secs / float(v: r.requests) else 0.0}))\n'
                '  |> group(columns: ["op", "warp_id", "endpoint"])\n'
                '  |> yield(name: "mean_latency")')
    ttfb = mean_lat.replace('"request_total_secs"', '"request_ttfb_total_secs"').replace("r.request_total_secs", "r.request_ttfb_total_secs")
    cum = lambda f: (base + f'  |> filter(fn: (r) => r._field == "{f}")\n'
                     '  |> group(columns: ["op", "warp_id", "endpoint"])\n'
                     '  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)\n'
                     '  |> yield(name: "cumulative")')
    summary = ('from(bucket: v.defaultBucket)\n'
               '  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
               '  |> filter(fn: (r) => r._measurement == "warp_run_summary")\n'
               '  |> filter(fn: (r) => r.op =~ /^${op:regex}$/ and r.warp_id =~ /^${warp_id:regex}$/)\n'
               '  |> last()\n'
               '  |> toFloat()\n'
               '  |> group(columns: ["op", "warp_id"])\n'
               '  |> pivot(rowKey: ["op", "warp_id"], columnKey: ["_field"], valueColumn: "_value")\n'
               '  |> drop(columns: ["_start", "_stop", "_measurement", "_time"])')

    v_op = {"name": "op", "label": "operation", "type": "query", "datasource": INFLUX, "refresh": 2,
            "query": 'import "influxdata/influxdb/schema"\nschema.tagValues(bucket: v.defaultBucket, tag: "op")',
            "includeAll": True, "multi": True, "allValue": ".+", "current": {"text": "All", "value": "$__all"}}
    v_id = {"name": "warp_id", "label": "warp run id", "type": "query", "datasource": INFLUX, "refresh": 2,
            "query": 'import "influxdata/influxdb/schema"\nschema.tagValues(bucket: v.defaultBucket, tag: "warp_id")',
            "includeAll": True, "multi": True, "allValue": ".+", "current": {"text": "All", "value": "$__all"}}
    ann = [{"name": "warp runs", "enable": True, "iconColor": "orange", "datasource": {"type": "grafana", "uid": "-- Grafana --"},
            "target": {"type": "tags", "tags": ["warp"], "limit": 100, "matchAny": False}}]
    d = Dash("warp-client", "warp — client (InfluxDB)", ["warp", "influxdb"], [v_op, v_id], ann, refresh="5s", time_from="now-1h")
    d.row("Client-side throughput (warp writes cumulative totals; panels use derivative())")
    d.add(ts("Operations/s", [fq(rate_of("requests"))], "ops", ds=INFLUX, legend_calcs=["mean", "max"]), 12, 8)
    d.add(ts("Throughput", [fq(rate_of("bytes_total"))], "Bps", ds=INFLUX, legend_calcs=["mean", "max"]), 12, 8)
    d.add(ts("Errors/s", [fq(rate_of("errors"))], "ops", ds=INFLUX, minv=0, thresholds=RED_AT(0.01),
             desc="A run with any errors is not comparable (methodology: error-free runs only)."), 8, 8)
    d.add(ts("Mean request latency (per interval)", [fq(mean_lat)], "s", ds=INFLUX, legend_calcs=["mean", "max"],
             desc="warp streams only totals, so the mean per interval = Δrequest_total_secs / Δrequests. Percentiles and max are NOT in "
                  "InfluxDB: take them from `warp analyze` (meta in bench/results) or from the server dashboard (histograms)."), 8, 8)
    d.add(ts("Mean time to first byte (GET)", [fq(ttfb)], "s", ds=INFLUX), 8, 8)
    d.row("Totals")
    d.add(ts("Objects (cumulative)", [fq(cum("objects"))], "short", ds=INFLUX, fill=0), 8, 8)
    d.add(ts("Bytes (cumulative)", [fq(cum("bytes_total"))], "bytes", ds=INFLUX, fill=0), 8, 8)
    d.add(ts("Errors (cumulative)", [fq(cum("errors"))], "short", ds=INFLUX, fill=0), 8, 8)
    d.row("Run summaries (written by warp at the end of each run)")
    d.add(table("warp_run_summary", [fq(summary)], INFLUX,
                desc="Per run and operation: totals, and mean/min/max request latency (seconds)."), 24, 9)
    d.save("warp-client.json")


if __name__ == "__main__":
    server()
    warp()
