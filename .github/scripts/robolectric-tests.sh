#!/usr/bin/env bash
# Run the app Robolectric unit tests deterministically.
#
# Robolectric downloads its instrumented `android-all` SDK jars (API 26, 30, 34
# here) from Maven at test time. In CI that fetch is network-flaky and makes the
# whole :app:testDebugUnitTest task fail even when the code is correct. Two
# mechanisms make this deterministic WITHOUT weakening or skipping any test:
#
#   1. The caller caches ~/.m2/repository so the SDK jars persist across runs;
#      after the first successful fetch the tests run fully offline.
#   2. This wrapper retries ONLY when every failure in the JUnit reports is a
#      classified android-all/Maven download fault (see
#      classify-robolectric-failures.py). A genuine assertion/crash failure is
#      reported immediately and never retried, so real regressions still fail CI.
#
# No test is disabled, no failure is swallowed: the retry is scoped strictly to
# the infrastructure flake and bounded.
set -uo pipefail

TASK=":app:testDebugUnitTest"
REPORT_DIR="app/build/test-results/testDebugUnitTest"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAX_ATTEMPTS="${ROBOLECTRIC_MAX_ATTEMPTS:-3}"

attempt=1
while :; do
  echo "::group::$TASK (attempt $attempt/$MAX_ATTEMPTS)"
  # --rerun forces the tests to execute even if Gradle considers them UP-TO-DATE
  # from a previous attempt in this job, so a retry genuinely re-runs them.
  ./gradlew "$TASK" --console=plain --rerun-tasks
  rc=$?
  echo "::endgroup::"

  if [ "$rc" -eq 0 ]; then
    echo "App Robolectric tests passed on attempt $attempt."
    exit 0
  fi

  verdict="$(python3 "$SCRIPT_DIR/classify-robolectric-failures.py" "$REPORT_DIR" 2>/dev/null || echo REAL)"
  echo "Failure classification: $verdict (gradle rc=$rc)"

  if [ "$verdict" != "RETRYABLE" ]; then
    echo "::error::App Robolectric tests failed with a genuine (non-network) failure; not retrying. See the test report."
    exit "$rc"
  fi

  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "::error::Robolectric android-all SDK download kept failing after $attempt attempts. This is an infrastructure/network fault (MavenArtifactFetcher), not a test regression. Re-run the job."
    exit "$rc"
  fi

  backoff=$(( attempt * attempt * 5 ))
  echo "::warning::Robolectric SDK fetch flake detected (android-all / MavenArtifactFetcher network error). Retrying in ${backoff}s."
  sleep "$backoff"
  attempt=$(( attempt + 1 ))
done
