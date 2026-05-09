#!/usr/bin/env bash
#
# MemTSDB JMeter load test — concurrent ingestion + latency (HTML dashboard + JTL).
#
# Prerequisites:
#   - MemTSDB running (e.g. java -jar target/memtsdb-1.0.0-SNAPSHOT.jar)
#   - Apache JMeter 5.x on PATH as `jmeter` (or set JMETER_HOME)
#
# Default: 5,000,000 samples = threads × loops (100 × 50,000)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLAN="${ROOT}/benchmark/jmeter/memtsdb-ingest.jmx"
OUT_DIR="${ROOT}/benchmark/results/run-$(date +%Y%m%d-%H%M%S)"
JTL="${OUT_DIR}/results.jtl"
HTML="${OUT_DIR}/html-report"

THREADS="${THREADS:-100}"
RAMP_SEC="${RAMP_SEC:-30}"
HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
TARGET_SAMPLES="${TARGET_SAMPLES:-5000000}"

# Smoke mode: quick sanity check (~1k samples)
if [[ "${SMOKE:-0}" == "1" ]]; then
  THREADS=10
  TARGET_SAMPLES=1000
fi

if ! command -v jmeter >/dev/null 2>&1; then
  if [[ -n "${JMETER_HOME:-}" && -x "${JMETER_HOME}/bin/jmeter" ]]; then
    export PATH="${JMETER_HOME}/bin:${PATH}"
  else
    echo "ERROR: 'jmeter' not found. Install Apache JMeter and add it to PATH, or set JMETER_HOME." >&2
    echo "  macOS: brew install jmeter" >&2
    echo "  Or download: https://jmeter.apache.org/download_jmeter.cgi" >&2
    exit 1
  fi
fi

mkdir -p "${OUT_DIR}"

LOOPS=$((TARGET_SAMPLES / THREADS))
if [[ $((THREADS * LOOPS)) -ne "${TARGET_SAMPLES}" ]]; then
  echo "ERROR: TARGET_SAMPLES (${TARGET_SAMPLES}) must be divisible by THREADS (${THREADS})." >&2
  echo "  Example: TARGET_SAMPLES=5000000 THREADS=125  → loops=40000" >&2
  exit 1
fi

echo "=== MemTSDB JMeter load test ==="
echo "  Plan:        ${PLAN}"
echo "  Output:      ${OUT_DIR}"
echo "  Target URL:  http://${HOST}:${PORT}/api/v1/metrics"
echo "  Threads:     ${THREADS}"
echo "  Loops:       ${LOOPS}"
echo "  Ramp (s):    ${RAMP_SEC}"
echo "  Samples:     $((THREADS * LOOPS))"
echo ""

cd "${ROOT}"

# JTL fields suitable for HTML report + latency percentiles
export JVM_ARGS="${JVM_ARGS:-} -Xms512m -Xmx2g"

jmeter -n \
  -t "${PLAN}" \
  -l "${JTL}" \
  -e \
  -o "${HTML}" \
  -j "${OUT_DIR}/jmeter.log" \
  -Jthreads="${THREADS}" \
  -Jloops="${LOOPS}" \
  -Jramp_sec="${RAMP_SEC}" \
  -Jhost="${HOST}" \
  -Jport="${PORT}"

echo ""
echo "Done."
echo "  JTL:          ${JTL}"
echo "  HTML report:  ${HTML}/index.html"
echo "  JMeter log:   ${OUT_DIR}/jmeter.log"
echo ""
echo "Open the HTML report for throughput, latency percentiles (p90/p95/p99), and errors."
