#!/usr/bin/env python3
"""Tiny S3 client for the fault tests (boto3, path style, no retries). Environment: FAULT_ENDPOINT, FAULT_ACCESS_KEY,
FAULT_SECRET_KEY. Prints machine-readable lines; exit 0 unless the operation itself failed unexpectedly.

  s3c.py mb BUCKET
  s3c.py put BUCKET KEY FILE              -> "PUT ok" | "PUT error STATUS CODE"
  s3c.py get BUCKET KEY                   -> "GET absent" | "GET ok SIZE SHA256" | "GET error STATUS CODE|exception"
  s3c.py mpu-parts BUCKET KEY FILE PARTMIB [STOP_AFTER]   upload parts (prints "PART n" per part; "UPLOADID id" first)
  s3c.py mpu-resume BUCKET KEY FILE PARTMIB UPLOADID      list parts, upload the missing ones, complete
  s3c.py mpu-complete-only BUCKET KEY FILE PARTMIB        whole multipart upload, prints "COMPLETING" before Complete
  s3c.py list-uploads BUCKET
"""
import hashlib, os, sys
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

c = boto3.client("s3", endpoint_url=os.environ["FAULT_ENDPOINT"], aws_access_key_id=os.environ["FAULT_ACCESS_KEY"],
                 aws_secret_access_key=os.environ["FAULT_SECRET_KEY"], region_name="us-east-1",
                 config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}, connect_timeout=10, read_timeout=300))


def err(e):
    r = e.response
    return f'{r["ResponseMetadata"]["HTTPStatusCode"]} {r["Error"].get("Code")}'


def parts_of(path, mib):
    size = os.path.getsize(path)
    ps = mib * 1048576
    n = max(1, (size + ps - 1) // ps)
    return [(i + 1, i * ps, min(ps, size - i * ps)) for i in range(n)]


def read(path, off, n):
    with open(path, "rb") as f:
        f.seek(off)
        return f.read(n)


def main():
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "mb":
        try:
            c.create_bucket(Bucket=a[0]); print("MB ok")
        except ClientError as e:
            print("MB error", err(e))
    elif cmd == "put":
        try:
            with open(a[2], "rb") as f:
                c.put_object(Bucket=a[0], Key=a[1], Body=f)
            print("PUT ok")
        except ClientError as e:
            print("PUT error", err(e), e.response["Error"].get("Message", "")[:160])
        except Exception as e:  # connection reset etc.
            print("PUT exception", type(e).__name__)
    elif cmd == "get":
        try:
            r = c.get_object(Bucket=a[0], Key=a[1])
            h = hashlib.sha256(); n = 0
            for chunk in r["Body"].iter_chunks(1 << 20):
                h.update(chunk); n += len(chunk)
            print("GET ok", n, h.hexdigest())
        except ClientError as e:
            print("GET absent" if e.response["Error"].get("Code") in ("NoSuchKey", "404") else "GET error " + err(e))
        except Exception as e:
            print("GET exception", type(e).__name__, str(e)[:120])
    elif cmd == "mpu-parts":
        bucket, key, path, mib = a[0], a[1], a[2], int(a[3])
        stop = int(a[4]) if len(a) > 4 else None
        uid = c.create_multipart_upload(Bucket=bucket, Key=key)["UploadId"]
        print("UPLOADID", uid, flush=True)
        for n, off, ln in parts_of(path, mib):
            c.upload_part(Bucket=bucket, Key=key, UploadId=uid, PartNumber=n, Body=read(path, off, ln))
            print("PART", n, flush=True)
            if stop and n >= stop:
                return
        print("MPU parts done")
    elif cmd in ("mpu-resume", "mpu-complete-only"):
        bucket, key, path, mib = a[0], a[1], a[2], int(a[3])
        have = {}
        if cmd == "mpu-resume":
            uid = a[4]
            try:
                for p in c.list_parts(Bucket=bucket, Key=key, UploadId=uid).get("Parts", []):
                    have[p["PartNumber"]] = p["ETag"]
            except ClientError as e:
                print("RESUME error", err(e)); return
        else:
            uid = c.create_multipart_upload(Bucket=bucket, Key=key)["UploadId"]
        done = []
        for n, off, ln in parts_of(path, mib):
            if n in have:
                done.append({"PartNumber": n, "ETag": have[n]}); continue
            r = c.upload_part(Bucket=bucket, Key=key, UploadId=uid, PartNumber=n, Body=read(path, off, ln))
            done.append({"PartNumber": n, "ETag": r["ETag"]})
        print("COMPLETING", len(have), "parts were already there", flush=True)
        try:
            c.complete_multipart_upload(Bucket=bucket, Key=key, UploadId=uid, MultipartUpload={"Parts": done})
            print("COMPLETE ok")
        except ClientError as e:
            print("COMPLETE error", err(e))
        except Exception as e:
            print("COMPLETE exception", type(e).__name__)
    elif cmd == "list-uploads":
        r = c.list_multipart_uploads(Bucket=a[0])
        for u in r.get("Uploads", []):
            print("UPLOAD", u["Key"], u["UploadId"])
        print("UPLOADS", len(r.get("Uploads", [])))
    else:
        sys.exit("unknown command " + cmd)


main()
