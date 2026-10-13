#!/usr/bin/env python3
"""Regression contract between the Android branch and the shared KMP core.

Every core test of the Android branch (java.time) must exist in the shared
core (core/src/commonTest, kotlinx-datetime) and say the same thing: after
the mechanical type translation below, a test body may differ only in type
spellings, never in inputs, expectations or assertions.

    python3 tools/kmp_test_contract.py [android-ref]   # default: origin/claude/mama-phone-lock-app-vzccj7

Prints every missing test and every changed line; exits 1 if a test is missing.
"""
import difflib
import re
import subprocess
import sys

ANDROID_REF = sys.argv[1] if len(sys.argv) > 1 else "origin/claude/mama-phone-lock-app-vzccj7"
ANDROID_DIR = "core/src/test/kotlin/app/mama/core/"
SHARED_DIR = "core/src/commonTest/kotlin/app/mama/core/"
UNITS = {"Hours": "hours", "Minutes": "minutes", "Days": "days", "Seconds": "seconds", "Millis": "milliseconds"}


def close_paren(s, i):
    depth = 0
    for j in range(i, len(s)):
        depth += {"(": 1, ")": -1}.get(s[j], 0)
        if depth == 0:
            return j
    raise ValueError("unbalanced parentheses")


def split_args(a):
    depth, parts, cur = 0, [], ""
    for ch in a:
        depth += {"(": 1, "[": 1, ")": -1, "]": -1}.get(ch, 0)
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    return parts + [cur.strip()]


def test_name(n):
    """Kotlin/Native does not allow commas or parentheses in function names."""
    return n.replace("only generic defaults (no SIM)", "only generic defaults with no SIM").replace(", ", " - ").replace(",", " ")


def translate(s):
    """java.time -> kotlinx-datetime / kotlin.time, as done for the shared core."""
    while (m := re.search(r"Duration\.of(Hours|Minutes|Days|Seconds|Millis)\(", s)):
        c = close_paren(s, m.end() - 1)
        arg = s[m.end():c].strip()
        arg = arg if re.fullmatch(r"[\w.]+", arg) else f"({arg})"
        s = s[:m.start()] + f"{arg}.{UNITS[m.group(1)]}" + s[c + 1:]
    while (m := re.search(r"Duration\.between\(", s)):
        c = close_paren(s, m.end() - 1)
        a, b = split_args(s[m.end():c])
        s = s[:m.start()] + f"({b} - {a})" + s[c + 1:]
    s = re.sub(r"LocalTime\.of\(", "LocalTime(", s).replace("LocalTime.NOON", "LocalTime(12, 0)")
    s = s.replace(".toMillis()", ".inWholeMilliseconds").replace("ZoneId.of(", "TimeZone.of(")
    s = re.sub(r"\bZoneId\b", "TimeZone", s)
    s = re.sub(r"fun `([^`]*)`", lambda m: "fun `" + test_name(m.group(1)) + "`", s)
    return s


def body(s):
    return [l for l in s.split("\n") if not l.startswith("import ") and l.strip()]


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


missing, changed = [], 0
files = [f for f in git("ls-tree", "--name-only", ANDROID_REF, ANDROID_DIR).split() if f.endswith(".kt")]
for f in files:
    name = f.rsplit("/", 1)[1]
    android = git("show", f"{ANDROID_REF}:{f}")
    try:
        shared = open(SHARED_DIR + name).read()
    except FileNotFoundError:
        shared = ""
    shared_tests = set(re.findall(r"@Test\s+fun `([^`]*)`", shared))
    for t in re.findall(r"@Test\s+fun `([^`]*)`", android):
        if test_name(t) not in shared_tests:
            missing.append(f"{name}: {t}")
    diff = [l for l in difflib.unified_diff(body(translate(android)), body(shared), lineterm="", n=0)
            if l[:1] in "+-" and not l.startswith(("+++", "---"))]
    if diff:
        changed += len(diff)
        print(f"== {name}: {len(diff)} line(s) differ beyond the type translation")
        print("\n".join(diff))

total = sum(len(re.findall(r"@Test\s+fun `", git("show", f"{ANDROID_REF}:{f}"))) for f in files)
print(f"Android core tests: {total}, missing in the shared core: {len(missing)}, differing lines: {changed}")
for m in missing:
    print("MISSING", m)
sys.exit(1 if missing else 0)
