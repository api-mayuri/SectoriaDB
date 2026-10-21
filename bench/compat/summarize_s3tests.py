#!/usr/bin/env python3
"""summarize_s3tests.py OUT_DIR S3TESTS_COMMIT: pass/fail counts and failure categories from junit.xml."""
import collections, json, re, sys, xml.etree.ElementTree as ET

out, commit = sys.argv[1], sys.argv[2]
root = ET.parse(out + "/junit.xml").getroot()
cases = list(root.iter("testcase"))
res = collections.Counter()
failed = []
for c in cases:
    if c.find("failure") is not None or c.find("error") is not None:
        node = c.find("failure") if c.find("failure") is not None else c.find("error")
        msg = (node.get("message") or "")[:300]
        res["failed"] += 1
        failed.append((c.get("name"), msg))
    elif c.find("skipped") is not None:
        res["skipped"] += 1
    else:
        res["passed"] += 1


def category(msg):
    m = re.search(r"ClientError: An error occurred \((\w+)\)(?: when calling the (\w+) operation)?", msg)
    if m:
        return f"S3 error {m.group(1)}" + (f" in {m.group(2)}" if m.group(2) else "")
    m = re.search(r"(AssertionError|KeyError|AttributeError|TypeError|ParamValidationError|\w+Error|\w+Exception)", msg)
    return m.group(1) if m else (msg.split("\n")[0][:80] or "other")


cats = collections.Counter(category(m) for _, m in failed)
summary = {"s3_tests_commit": commit, "total": len(cases), **res,
           "top_failure_categories": cats.most_common(15), "failed_tests": [n for n, _ in failed]}
json.dump(summary, open(out + "/summary.json", "w"), indent=1)
print(f"s3-tests {commit}: total={len(cases)} passed={res['passed']} failed={res['failed']} skipped={res['skipped']}")
for k, v in cats.most_common(15):
    print(f"  {v:4}  {k}")
