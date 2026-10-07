#!/usr/bin/env python3
"""Security / robustness probes against a running SectoriaDB (own SigV4 signer, raw HTTP, no SDK normalisation).

Each probe prints PASS / FAIL / INFO. FAIL means an unexpected server behaviour (5xx, crash, data leak, silent wrong
bytes); INFO documents behaviour that is by design or a judgement call. Exit code 1 if any FAIL.

Environment: FAULT_ENDPOINT (http://host:port), FAULT_ACCESS_KEY/SECRET_KEY (main), FAULT_ALT_ACCESS_KEY/SECRET_KEY,
SECURITY_MGMT (management port URL, optional), SECURITY_SLOW_CONNS (default 220), SECURITY_LIST_OBJECTS (default 20000).
"""
import datetime as dt, hashlib, hmac, http.client, os, re, socket, ssl, sys, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor

EP = urllib.parse.urlparse(os.environ.get("FAULT_ENDPOINT", "http://localhost:8090"))
HOST, PORT = EP.hostname, EP.port or 80
MAIN = (os.environ.get("FAULT_ACCESS_KEY", ""), os.environ.get("FAULT_SECRET_KEY", ""))
ALT = (os.environ.get("FAULT_ALT_ACCESS_KEY", ""), os.environ.get("FAULT_ALT_SECRET_KEY", ""))
MGMT = os.environ.get("SECURITY_MGMT", "")
results = {"PASS": 0, "FAIL": 0, "INFO": 0}


def out(kind, msg):
    results[kind] += 1
    print(f"{kind:4}  {msg}", flush=True)


def _h(k, m):
    return hmac.new(k, m.encode(), hashlib.sha256).digest()


def sign(method, raw_path, query="", headers=None, body=b"", creds=MAIN, now=None, payload_hash=None, region="us-east-1"):
    """Returns (headers) with an Authorization header. raw_path is used AS GIVEN for the canonical URI (single-encoded)."""
    now = now or dt.datetime.now(dt.timezone.utc)
    amz = now.strftime("%Y%m%dT%H%M%SZ"); day = now.strftime("%Y%m%d")
    ph = payload_hash or hashlib.sha256(body).hexdigest()
    hd = {"host": f"{HOST}:{PORT}", "x-amz-date": amz, "x-amz-content-sha256": ph}
    for k, v in (headers or {}).items():
        hd[k.lower()] = v
    signed = sorted(hd)
    canon_q = "&".join(sorted(query.split("&"))) if query else ""
    canon = "\n".join([method, raw_path, canon_q, "".join(f"{k}:{hd[k].strip()}\n" for k in signed), ";".join(signed), ph])
    scope = f"{day}/{region}/s3/aws4_request"
    sts = "\n".join(["AWS4-HMAC-SHA256", amz, scope, hashlib.sha256(canon.encode()).hexdigest()])
    k = _h(_h(_h(_h(("AWS4" + creds[1]).encode(), day), region), "s3"), "aws4_request")
    sig = hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()
    hd["authorization"] = f"AWS4-HMAC-SHA256 Credential={creds[0]}/{scope}, SignedHeaders={';'.join(signed)}, Signature={sig}"
    return hd


def call(method, raw_path, query="", headers=None, body=b"", creds=MAIN, signed=True, now=None, payload_hash=None, timeout=60):
    hd = sign(method, raw_path, query, headers, body, creds, now, payload_hash) if signed else {"host": f"{HOST}:{PORT}", **(headers or {})}
    c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    try:
        c.putrequest(method, raw_path + ("?" + query if query else ""), skip_host=True, skip_accept_encoding=True)
        for k, v in hd.items():
            c.putheader(k, v)
        if "content-length" not in {k.lower() for k in hd}:
            c.putheader("Content-Length", str(len(body)))
        c.endheaders(body)
        r = c.getresponse()
        data = r.read()
        return r.status, dict(r.getheaders()), data
    except Exception as e:
        return -1, {}, repr(e).encode()
    finally:
        c.close()


def code(data):
    m = re.search(rb"<Code>([^<]+)</Code>", data)
    return m.group(1).decode() if m else ""


def alive():
    s, _, _ = call("GET", "/", signed=False, timeout=10)
    return s in (200, 403)


# ------------------------------------------------------------------------------------------------------------
B = "sec-main"


def setup():
    s, _, d = call("PUT", f"/{B}")
    out("PASS" if s == 200 else "FAIL", f"setup: create bucket {B}: {s} {code(d)}")


def probe_auth():
    print("-- authentication and clock skew")
    s, _, d = call("GET", f"/{B}", signed=False)
    out("PASS" if s == 403 else "FAIL", f"anonymous request is rejected: {s} {code(d)}")
    s, _, d = call("GET", f"/{B}", creds=(MAIN[0], "wrong" + MAIN[1]))
    out("PASS" if s == 403 and code(d) == "SignatureDoesNotMatch" else "FAIL", f"wrong secret: {s} {code(d)}")
    s, _, d = call("GET", f"/{B}", creds=("NOSUCHKEY000000001", "x" * 32))
    out("PASS" if s == 403 and code(d) == "InvalidAccessKeyId" else "FAIL", f"unknown access key: {s} {code(d)}")
    now = dt.datetime.now(dt.timezone.utc)
    for minutes, expect_ok in ((-14, True), (14, True), (-16, False), (16, False), (-60 * 24, False)):
        s, _, d = call("GET", f"/{B}", now=now + dt.timedelta(minutes=minutes))
        good = (s == 200) if expect_ok else (s == 403 and code(d) == "RequestTimeTooSkewed")
        out("PASS" if good else "FAIL", f"clock skew {minutes:+d} min: {s} {code(d)} (window is 15 min)")
    # tampered payload: signed hash of other bytes
    s, _, d = call("PUT", f"/{B}/tamper", body=b"hello", payload_hash=hashlib.sha256(b"other").hexdigest())
    out("PASS" if s == 400 and code(d) == "XAmzContentSHA256Mismatch" else "FAIL", f"body does not match signed sha256: {s} {code(d)}")
    s, _, d = call("GET", f"/{B}/tamper")
    out("PASS" if s == 404 else "FAIL", f"... and nothing was stored: {s}")
    # query / header tampering: sign one path, send another
    hd = sign("GET", f"/{B}/tamper")
    c = http.client.HTTPConnection(HOST, PORT, timeout=10)
    c.putrequest("GET", f"/{B}/other", skip_host=True, skip_accept_encoding=True)
    for k, v in hd.items(): c.putheader(k, v)
    c.endheaders(); r = c.getresponse(); d = r.read(); c.close()
    out("PASS" if r.status == 403 else "FAIL", f"signature of /tamper replayed for /other: {r.status} {code(d)}")
    # header injection: a bare CR/LF-terminated extra header inside a metadata value, sent over a raw socket
    hd = sign("PUT", f"/{B}/inj", headers={"x-amz-meta-x": "a"}, body=b"x")
    raw = (f"PUT /{B}/inj HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in hd.items() if k != "x-amz-meta-x")
           + "x-amz-meta-x: a\r\nSet-Cookie: pwn=1\r\nContent-Length: 1\r\n\r\nx").encode()
    sck = socket.create_connection((HOST, PORT), timeout=10); sck.sendall(raw)
    resp = sck.recv(65536); sck.close()
    status = resp.split(b" ", 2)[1].decode() if resp.startswith(b"HTTP/") else "?"
    s2, h2, _ = call("HEAD", f"/{B}/inj")
    out("PASS" if "set-cookie" not in {k.lower() for k in h2} else "FAIL", f"injected extra header line in a signed request: status {status}, never reflected as a response header (HEAD: {s2})")


def probe_paths():
    print("-- keys: traversal, normalisation, unicode")
    cases = {
        "dotdot": "../escape", "dotdot-mid": "a/../../escape", "dot": "./dot", "double-slash": "a//b", "trailing-slash": "dir/",
        "encoded-dotdot": "%2e%2e/enc", "backslash": "a\\b", "space": "a b", "plus": "a+b", "percent": "100%25",
        "unicode-nfc": "café", "unicode-nfd": "café", "emoji": "\U0001F600/\U0001F4A9", "rtl": "‮ش", "cjk": "日本語/ファイル",
        "ctrl": "tab\there", "leading-slash": "/lead",
    }
    for name, key in cases.items():
        enc = urllib.parse.quote(key, safe="/~") if name not in ("encoded-dotdot",) else key
        body = f"payload-{name}".encode()
        s, _, d = call("PUT", f"/{B}/{enc}", body=body)
        if s >= 500 or s == -1:
            out("FAIL", f"{name}: PUT {key!r} -> {s} {code(d)}"); continue
        if s != 200:
            out("PASS", f"{name}: PUT {key!r} rejected with {s} {code(d)}"); continue
        s2, _, d2 = call("GET", f"/{B}/{enc}")
        out("PASS" if s2 == 200 and d2 == body else "FAIL", f"{name}: PUT {key!r} stored and read back unchanged: GET {s2}")
    # NFC and NFD are different keys in S3 (byte-wise); they must not collide
    a = call("GET", f"/{B}/" + urllib.parse.quote("café"))[2]; b = call("GET", f"/{B}/" + urllib.parse.quote("café"))[2]
    out("PASS" if a != b else "FAIL", "NFC and NFD spellings are distinct keys")
    s, _, d = call("PUT", f"/{B}/" + "k" * 1025, body=b"x")
    out("PASS" if s == 400 and code(d) == "KeyTooLongError" else ("INFO" if s < 500 else "FAIL"), f"key of 1025 bytes: {s} {code(d)} (S3: 400 KeyTooLongError)")
    s, _, d = call("PUT", f"/{B}/nul%00byte", body=b"x")
    out("PASS" if s < 500 and s != -1 else "FAIL", f"NUL byte in key: {s} {code(d)}")
    s, _, d = call("GET", f"/{B}/..%2f..%2fetc%2fpasswd")
    out("PASS" if s != 200 or b"root:" not in d else "FAIL", f"traversal in GET path: {s} {code(d)}")
    for bad in ("..", "a", "UPPER", "a_b", "-lead", "1.2.3.4", "a..b", "x" * 64):
        s, _, d = call("PUT", f"/{bad}")
        out("PASS" if s == 400 or s == 404 or s == 405 else "FAIL", f"invalid bucket name {bad[:20]!r}: {s} {code(d)}")


def probe_limits():
    print("-- oversized headers, bodies, parts")
    s, _, d = call("PUT", f"/{B}/meta", headers={"x-amz-meta-big": "v" * 4096}, body=b"x")
    out("PASS" if s == 400 else "INFO", f"user metadata of 4 KiB (S3 limit 2 KB): {s} {code(d)}")
    s, _, d = call("PUT", f"/{B}/hdr", headers={"x-amz-meta-huge": "v" * 70000}, body=b"x")
    out("PASS" if s in (400, 431) else ("FAIL" if s >= 500 or s == -1 else "INFO"), f"one header of 70 KB: {s} {code(d)}")
    hdrs = {f"x-amz-meta-h{i}": "v" for i in range(400)}
    s, _, d = call("PUT", f"/{B}/manyhdr", headers=hdrs, body=b"x")
    out("PASS" if s in (400, 431) else ("FAIL" if s >= 500 or s == -1 else "INFO"), f"400 metadata headers: {s} {code(d)}")
    # claimed Content-Length of 6 TiB (not sent): must be refused or time out, not allocate / hang forever
    t = time.time()
    s, _, d = call("PUT", f"/{B}/claimed", headers={"content-length": str(6 * 1024 ** 4)}, body=b"", timeout=15, payload_hash="UNSIGNED-PAYLOAD")
    out("PASS" if s in (400, 411, 413, -1) or s >= 400 else "INFO", f"Content-Length 6 TiB without a body: {s} {code(d)} after {time.time()-t:.1f}s")
    # multipart: part numbers
    s, _, d = call("POST", f"/{B}/mp", query="uploads")
    uid = re.search(rb"<UploadId>([^<]+)", d).group(1).decode() if s == 200 else ""
    for pn, ok in ((0, False), (1, True), (10000, True), (10001, False)):
        s, _, d = call("PUT", f"/{B}/mp", query=f"partNumber={pn}&uploadId={uid}", body=b"p" * 100)
        good = (s == 200) if ok else (s == 400)
        out("PASS" if good else "FAIL", f"UploadPart partNumber={pn}: {s} {code(d)} (valid range 1..10000)")
    # Complete with a small non-last part (S3: EntityTooSmall) and with an unknown part
    xml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>x</ETag></Part><Part><PartNumber>10000</PartNumber><ETag>y</ETag></Part></CompleteMultipartUpload>"
    s, _, d = call("POST", f"/{B}/mp", query=f"uploadId={uid}", body=xml.encode())
    out("PASS" if 400 <= s < 500 else "FAIL", f"Complete with wrong ETags / tiny non-last part: {s} {code(d)}")
    big = "<CompleteMultipartUpload>" + "".join(f"<Part><PartNumber>{i}</PartNumber><ETag>x</ETag></Part>" for i in range(1, 10002)) + "</CompleteMultipartUpload>"
    s, _, d = call("POST", f"/{B}/mp", query=f"uploadId={uid}", body=big.encode())
    out("PASS" if 400 <= s < 500 else "FAIL", f"Complete listing 10001 parts: {s} {code(d)}")
    s, _, d = call("POST", f"/{B}/mp", query=f"uploadId={uid}", body=(b"<CompleteMultipartUpload>" + b"<Part>" * 3_000_000 + b"</CompleteMultipartUpload>"))
    out("PASS" if 400 <= s < 500 else ("FAIL" if s >= 500 or s == -1 else "INFO"), f"Complete with a 18 MB XML body: {s} {code(d)}")
    # DeleteObjects with 5000 keys (S3 limit 1000)
    dl = "<Delete>" + "".join(f"<Object><Key>k{i}</Key></Object>" for i in range(5000)) + "</Delete>"
    s, _, d = call("POST", f"/{B}", query="delete", body=dl.encode())
    out("PASS" if s == 400 else "INFO", f"DeleteObjects with 5000 keys (S3 limit 1000): {s} {code(d)}")
    s, _, d = call("GET", f"/{B}", query="max-keys=2147483647")
    out("PASS" if s == 200 else "FAIL", f"max-keys=2147483647 is capped, not an error or a huge response: {s} len={len(d)}")


def probe_xxe():
    print("-- XXE and entity expansion")
    xxe = b'<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><Delete><Object><Key>&x;</Key></Object></Delete>'
    s, _, d = call("POST", f"/{B}", query="delete", body=xxe)
    leaked = b"root:" in d
    out("PASS" if not leaked and s in (400, 200) else "FAIL", f"external entity in DeleteObjects: {s} {code(d)} leaked={leaked}")
    xxe2 = b'<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "http://127.0.0.1:1/ssrf">]><CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>&x;</ETag></Part></CompleteMultipartUpload>'
    s, _, d = call("POST", f"/{B}/mp", query="uploadId=nonexistent", body=xxe2)
    out("PASS" if b"root:" not in d and s < 500 else "FAIL", f"external entity in CompleteMultipartUpload: {s} {code(d)}")
    laughs = ('<?xml version="1.0"?><!DOCTYPE l [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">'
              '<!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;"><!ENTITY e "&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;"><!ENTITY f "&e;&e;&e;&e;&e;&e;&e;&e;&e;&e;">]>'
              '<Delete><Object><Key>&f;</Key></Object></Delete>').encode()
    t = time.time(); s, _, d = call("POST", f"/{B}", query="delete", body=laughs)
    out("PASS" if s < 500 and s != -1 and time.time() - t < 5 and alive() else "FAIL", f"entity expansion bomb: {s} {code(d)} in {time.time()-t:.1f}s")
    for sub, name in (("acl", "PutBucketAcl"), ("policy", "PutBucketPolicy")):
        s, _, d = call("PUT", f"/{B}", query=sub, body=xxe)
        out("PASS" if b"root:" not in d and s < 500 else "FAIL", f"external entity in {name}: {s} {code(d)}")


def probe_cross_account():
    print("-- cross-account access (SectoriaDB has no IAM: any valid key is the owner of everything)")
    call("PUT", f"/{B}/secret", body=b"main-only")
    s, _, d = call("GET", f"/{B}/secret", creds=ALT)
    out("INFO", f"alt key GET of main's object: {s} {code(d)}  (no IAM/ownership model: any authenticated key may read it; ACLs/policies can only open access further)")
    s, _, d = call("PUT", f"/{B}/by-alt", body=b"x", creds=ALT)
    out("INFO", f"alt key PUT into main's bucket: {s} {code(d)}")
    s, _, d = call("DELETE", f"/{B}/by-alt", creds=ALT)
    out("INFO", f"alt key DELETE in main's bucket: {s} {code(d)}")
    s, _, d = call("GET", "/", creds=ALT)
    out("INFO", f"alt key ListBuckets sees main's bucket: {B.encode() in d}")


def probe_list_perf():
    n = int(os.environ.get("SECURITY_LIST_OBJECTS", "20000"))
    print(f"-- List performance with {n} objects sharing a ~700 byte prefix")
    s, _, _ = call("PUT", "/sec-list")
    pre = "p" * 700 + "/"

    def put(i):
        return call("PUT", f"/sec-list/{pre}d{i % 50}/o{i:06d}", body=b"x")[0]
    t = time.time()
    with ThreadPoolExecutor(16) as ex:
        codes = list(ex.map(put, range(n)))
    bad = sum(1 for c in codes if c != 200)
    out("PASS" if bad == 0 else "FAIL", f"created {n} objects in {time.time()-t:.0f}s ({n/(time.time()-t):.0f}/s), failures: {bad}")
    for label, q, limit in (("full prefix, delimiter /", f"list-type=2&prefix={pre}&delimiter=/", 3),
                            ("full prefix, no delimiter, max-keys=1000", f"list-type=2&prefix={pre}&max-keys=1000", 3),
                            ("1500-byte prefix (no match)", "list-type=2&prefix=" + "q" * 1500, 3),
                            ("prefix that matches nothing", "list-type=2&prefix=zzz", 3),
                            ("empty prefix, 1000 keys", "list-type=2&max-keys=1000", 3)):
        t = time.time(); s, _, d = call("GET", "/sec-list", query=q.replace("/", "%2F")); el = time.time() - t
        out("PASS" if s in (200, 400) and el < limit else "FAIL", f"{label}: {s} {code(d)} in {el*1000:.0f} ms")
    # walk the whole listing by continuation tokens
    t = time.time(); cnt = 0; tok = ""; pages = 0
    while True:
        q = f"list-type=2&prefix={urllib.parse.quote(pre, safe='')}&max-keys=1000" + (f"&continuation-token={urllib.parse.quote(tok, safe='')}" if tok else "")
        s, _, d = call("GET", "/sec-list", query=q)
        if s != 200: break
        cnt += len(re.findall(rb"<Key>", d)); pages += 1
        m = re.search(rb"<NextContinuationToken>([^<]+)", d)
        if not m: break
        tok = m.group(1).decode()
    out("PASS" if cnt == n else "FAIL", f"full pagination: {cnt}/{n} keys in {pages} pages, {time.time()-t:.1f}s")


def probe_slow():
    n = int(os.environ.get("SECURITY_SLOW_CONNS", "220"))
    print(f"-- slow clients ({n} connections)")
    socks = []
    # (a) slowloris at the header stage: partial request line / headers, never finished
    for i in range(n):
        try:
            sck = socket.create_connection((HOST, PORT), timeout=5); sck.sendall(b"GET / HTTP/1.1\r\nHost: x\r\nX-a: "); socks.append(sck)
        except OSError:
            break
    t = time.time(); ok = alive(); el = time.time() - t
    out("PASS" if ok and el < 2 else "FAIL", f"{len(socks)} half-open HEADER connections: a normal request is still served in {el*1000:.0f} ms")
    for sck in socks: sck.close()
    # (b) authenticated clients sending a body at 1 byte/s (hold a worker thread each: Tomcat max 200)
    socks = []
    for i in range(n):
        try:
            hd = sign("PUT", f"/{B}/slow{i}", headers={"content-length": "1000000"}, payload_hash="UNSIGNED-PAYLOAD")
            sck = socket.create_connection((HOST, PORT), timeout=5)
            req = f"PUT /{B}/slow{i} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in hd.items()) + "\r\nx"
            sck.sendall(req.encode()); socks.append(sck)
        except OSError:
            break
    time.sleep(1)
    t = time.time(); s, _, d = call("GET", "/", signed=False, timeout=8); el = time.time() - t
    verdict = "PASS" if s in (200, 403) and el < 2 else "INFO"
    out(verdict, f"{len(socks)} authenticated slow-BODY uploads (1 byte, then silence): a new request gets {s} after {el:.1f}s"
        f"{'' if verdict == 'PASS' else ' -> worker threads are exhausted (server.tomcat.threads.max=200, connection-timeout=10m): DoS by any key holder'}")
    for sck in socks: sck.close()
    time.sleep(2)
    out("PASS" if alive() else "FAIL", "server serves requests again after the slow clients disconnected")


def metrics_summary():
    if not MGMT: return
    import urllib.request
    try:
        txt = urllib.request.urlopen(MGMT + "/actuator/prometheus", timeout=5).read().decode()
    except Exception as e:
        out("INFO", f"metrics not readable: {e}"); return
    fails = {m.group(1): float(m.group(2)) for m in re.finditer(r'sectoriadb_s3_auth_failures_total\{[^}]*reason="(\w+)"\} ([0-9.]+)', txt) if float(m.group(2)) > 0}
    out("INFO", f"auth failures counted by the server: {fails}")
    out("PASS" if fails.get("clock_skew", 0) >= 3 and fails.get("signature_mismatch", 0) >= 1 else "FAIL", "metrics saw the clock_skew / signature_mismatch probes")


if __name__ == "__main__":
    sel = sys.argv[1:] or ["auth", "paths", "limits", "xxe", "cross", "list", "slow"]
    setup()
    for name, fn in (("auth", probe_auth), ("paths", probe_paths), ("limits", probe_limits), ("xxe", probe_xxe),
                     ("cross", probe_cross_account), ("list", probe_list_perf), ("slow", probe_slow)):
        if name in sel:
            fn()
            if not alive():
                out("FAIL", f"server is not answering after the '{name}' probes")
                break
    metrics_summary()
    print(f"== security.py: {results['PASS']} passed, {results['FAIL']} failed, {results['INFO']} info")
    sys.exit(1 if results["FAIL"] else 0)
