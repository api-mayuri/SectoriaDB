#!/usr/bin/env python3
"""Writer and checker for snapshot-backup.sh (boto3, path style). Environment: FAULT_ENDPOINT, FAULT_ACCESS_KEY, FAULT_SECRET_KEY.

  snapshot_check.py write BUCKET LOG SECONDS [THREADS]   PUT / overwrite / DELETE random objects until SECONDS elapse or the
                                                         server stops answering. LOG gets "BEGIN|ACK key sha|-" lines (a
                                                         line is appended before a request and after its 2xx answer).
  snapshot_check.py check BUCKET LOG                      against a server started from a snapshot: for every key the object
                                                         must equal the state after its last ACK or any later BEGIN
                                                         (an unanswered request may or may not have been applied).
                                                         Prints "CHECK ok KEYS ACKED" or "CHECK FAILED ..." (exit 1).
"""
import hashlib, os, random, sys, threading, time
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

c = boto3.client("s3", endpoint_url=os.environ["FAULT_ENDPOINT"], aws_access_key_id=os.environ["FAULT_ACCESS_KEY"],
                 aws_secret_access_key=os.environ["FAULT_SECRET_KEY"], region_name="us-east-1",
                 config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}, connect_timeout=10, read_timeout=120))
ABSENT = "-"


def writer(bucket, log, deadline, tid, lock):
    rnd = random.Random(1000 + tid)
    n = 0
    while time.time() < deadline:
        n += 1
        key = f"t{tid}/k{rnd.randrange(40)}"  # a small key space per thread: overwrites and deletes happen
        if rnd.random() < 0.12:
            sha, op = ABSENT, "del"
        else:
            r = rnd.random()
            size = rnd.randrange(1, 64 << 10) if r < 0.7 else rnd.randrange(100 << 10, 3 << 20) if r < 0.95 else rnd.randrange(6 << 20, 20 << 20)
            data = rnd.randbytes(size)
            sha, op = hashlib.sha256(data).hexdigest(), "put"
        with lock:
            log.write(f"BEGIN {key} {sha}\n"); log.flush()
        try:
            if op == "put":
                c.put_object(Bucket=bucket, Key=key, Body=data)
            else:
                c.delete_object(Bucket=bucket, Key=key)
        except Exception:
            return  # the server is gone (or the request failed): this thread stops, the BEGIN stays unanswered
        with lock:
            log.write(f"ACK {key} {sha}\n"); log.flush()


def read_log(path):
    allowed, acked = {}, 0  # key -> set of acceptable states
    with open(path) as f:
        for line in f:
            p = line.split()
            if len(p) != 3:
                continue  # a torn last line
            kind, key, sha = p
            if kind == "BEGIN":
                allowed.setdefault(key, {ABSENT}).add(sha)
            elif kind == "ACK":
                allowed[key] = {sha}; acked += 1
    return allowed, acked


def check(bucket, log):
    allowed, acked = read_log(log)
    bad = []
    for key, ok in sorted(allowed.items()):
        try:
            r = c.get_object(Bucket=bucket, Key=key)
            h = hashlib.sha256()
            for chunk in r["Body"].iter_chunks(1 << 20):
                h.update(chunk)
            state = h.hexdigest()
        except ClientError as e:
            if e.response["Error"].get("Code") not in ("NoSuchKey", "404"):
                bad.append(f"{key}: GET error {e.response['Error'].get('Code')}"); continue
            state = ABSENT
        if state not in ok:
            bad.append(f"{key}: content {state[:12]} not in the acknowledged/in-flight states {sorted(s[:12] for s in ok)}")
    listed = {o["Key"] for page in c.get_paginator("list_objects_v2").paginate(Bucket=bucket) for o in page.get("Contents", [])}
    for key in sorted(listed - set(allowed)):
        bad.append(f"{key}: listed but never written")
    if bad:
        print("CHECK FAILED", len(bad), "of", len(allowed), "keys;", "; ".join(bad[:5])); sys.exit(1)
    print("CHECK ok", len(allowed), "keys,", acked, "acknowledged operations")


def main():
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "write":
        threads = int(a[3]) if len(a) > 3 else 4
        deadline = time.time() + float(a[2])
        lock = threading.Lock()
        with open(a[1], "a") as log:
            ts = [threading.Thread(target=writer, args=(a[0], log, deadline, i, lock)) for i in range(threads)]
            [t.start() for t in ts]; [t.join() for t in ts]
    elif cmd == "check":
        check(a[0], a[1])
    else:
        sys.exit("unknown command " + cmd)


main()
