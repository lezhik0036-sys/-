#!/usr/bin/env python3
"""Classify a failed Robolectric unit-test run as a retryable SDK-download
flake or a genuine test failure.

Robolectric resolves its instrumented `android-all` SDK jars (here API 26, 30
and 34) over the network at test time via `MavenArtifactFetcher` ->
`HttpURLConnection`. In CI that fetch is network-flaky: a dropped connection or
a slow mirror makes every test in the affected class error out even though the
code under test is fine. Those failures are infrastructure noise, not a
regression, and a retry resolves them.

This script reads the JUnit XML reports Gradle wrote for the run and decides:

  RETRYABLE  - there is at least one failure AND every failure is an
               android-all/Maven SDK-fetch network error. Safe to retry.
  REAL       - at least one failure is something else (an assertion, an NPE,
               a real crash). Must NOT be retried or masked.
  NONE       - no <failure>/<error> elements found (e.g. the build broke before
               any test ran, or a compile error). Treated as non-retryable by
               the caller.

Only RETRYABLE ever authorises a retry, so a true regression can never be
silently papered over by the surrounding wrapper.

Usage: classify-robolectric-failures.py <test-results-dir>
Prints exactly one verdict word to stdout.
"""
from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

# Signatures that mark a failure as an android-all SDK download / network fault.
# Matched case-insensitively against the failure message, type and stack text.
NETWORK_SIGNATURES = (
    "mavenartifactfetcher",
    "android-all-instrumented",
    "android-all",
    "org.robolectric.internal.dependency",
    "mavendependencyresolver",
    "dependency resolution failed",
    "couldn't load target sdk",
    "unable to resolve",
    # java.net / transport level causes that bubble up from the fetch
    "java.net.sockettimeoutexception",
    "java.net.unknownhostexception",
    "java.net.connectexception",
    "java.net.socketexception",
    "httpurlconnection",
    "connection reset",
    "read timed out",
    "connect timed out",
    "premature end of content-length",
    "unexpected end of stream",
    "javax.net.ssl.sslexception",
    "server returned http response code: 5",
    "503 for url",
    "502 for url",
    "429 for url",
    "failed to fetch",
    "error fetching",
)


def _text_of(elem: ET.Element) -> str:
    parts = [elem.get("message") or "", elem.get("type") or "", elem.text or ""]
    return "\n".join(parts).lower()


def classify(results_dir: str) -> str:
    xml_files = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
    if not xml_files:
        return "NONE"

    saw_failure = False
    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # A truncated/half-written report is itself suspicious but not proof
            # of a network flake; be conservative and treat the run as REAL.
            return "REAL"
        # Each <testcase> may carry <failure> and/or <error> children.
        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                for node in case.findall(kind):
                    saw_failure = True
                    blob = _text_of(node)
                    if not any(sig in blob for sig in NETWORK_SIGNATURES):
                        # A failure that is not an SDK-fetch fault: real.
                        return "REAL"
    return "RETRYABLE" if saw_failure else "NONE"


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("usage: classify-robolectric-failures.py <dir>\n")
        return 2
    verdict = classify(sys.argv[1])
    print(verdict)
    # Exit codes mirror the verdict for callers that prefer `$?` over stdout.
    return {"RETRYABLE": 0, "REAL": 1, "NONE": 3}[verdict]


if __name__ == "__main__":
    raise SystemExit(main())
