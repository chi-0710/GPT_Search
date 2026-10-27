"""Compare migrated read APIs without mutating data or printing report contents."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import re
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import ProxyHandler, build_opener


TIME_KEYS = {"next_cursor", "createdAt", "updatedAt", "started_at", "completed_at", "timestamp"}


def normalize(value, key=""):
    """Normalize equivalent ISO offsets; leave report and evidence text unchanged."""
    if isinstance(value, dict):
        return {name: normalize(item, name) for name, item in value.items()}
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if key in TIME_KEYS and isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc).isoformat()
        except ValueError:
            return value
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-url", default="http://127.0.0.1:8000")
    parser.add_argument("--java-url", default="http://127.0.0.1:8001")
    parser.add_argument("--sample-size", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.sample_size <= 20:
        parser.error("sample-size must be between 1 and 20")
    opener = build_opener(ProxyHandler({}))
    checked = 0

    def differing_fields(left, right, path="response"):
        if isinstance(left, dict) and isinstance(right, dict):
            missing = [path + "." + key + " (missing key)" for key in left.keys() ^ right.keys()]
            return missing + [field for key in left.keys() & right.keys()
                              for field in differing_fields(left[key], right[key], path + "." + key)]
        if isinstance(left, (list, tuple)) and isinstance(right, (list, tuple)):
            if len(left) != len(right):
                return [path + ".length"]
            return [field for index, (a, b) in enumerate(zip(left, right))
                    for field in differing_fields(a, b, f"{path}[{index}]")]
        return [] if left == right else [path]

    def fetch(origin, path, binary):
        try:
            response = opener.open(origin.rstrip("/") + path, timeout=30)
        except HTTPError as error:
            response = error
        with response:
            data = response.read()
            if not binary:
                data = normalize(json.loads(data))
            return response.status, data

    def compare(path, *, binary=False):
        nonlocal checked
        left = fetch(args.python_url, path, binary)
        right = fetch(args.java_url, path, binary)
        if left != right:
            raise AssertionError("Read API mismatch (content omitted): " + path
                                 + " fields=" + str(differing_fields(left, right)[:10]))
        checked += 1
        return left

    compare("/health/ready")
    _, listing = compare("/api/reports?include_incomplete=true&limit=" + str(args.sample_size))
    report_count = len(listing["reports"])
    evidence_count = 0
    for report in listing["reports"]:
        identifier = quote(report["id"], safe="")
        compare("/api/reports/" + identifier)
        compare("/api/reports/" + identifier + "/chat")
        for artifact in set(re.findall(r"/api/artifacts/[0-9a-fA-F-]{36}", json.dumps(report))):
            compare(artifact, binary=True)
        status, run = compare("/api/research-runs/" + identifier)
        if status == 200:
            run_id = quote(run["run"]["id"], safe="")
            compare("/api/research-runs/" + run_id)
            compare("/api/research-runs/" + run_id + "/events")
            compare("/api/research-runs/" + run_id + "/log")
            _, evidence = compare("/api/reports/" + identifier + "/evidence")
            evidence_count += len(evidence["evidence"])
            for item in evidence["evidence"][:args.sample_size]:
                compare(f"/api/research-runs/{run_id}/evidence/{item['number']}")
    _, documents = compare("/api/documents")
    for document in documents["documents"][:args.sample_size]:
        compare("/api/documents/" + quote(document["id"], safe="") + "/content", binary=True)
    compare("/api/reports/java-migration-missing-report")
    compare("/api/artifacts/not-a-uuid")
    compare("/api/documents/not-a-uuid/content")
    print(json.dumps({"checked": checked, "sampled_reports": report_count,
                      "listed_documents": len(documents["documents"]),
                      "listed_evidence": evidence_count, "result": "matched"}))


if __name__ == "__main__":
    main()
