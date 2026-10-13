#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source_dir=app/src/test/java/com/elewashy/nexa/downloadbenchmark
if [[ -e "$source_dir" ]]; then echo "Temporary benchmark sources already exist: $source_dir" >&2; exit 1; fi
trap 'rm -rf "$source_dir"' EXIT
mkdir -p "$source_dir/legacy"
# Compile the pre-rewrite baseline alongside the rewrite, without switching branches.
baseline=${NEXA_BENCH_BASELINE:-4b8e290}
for name in DownloadTask HttpProber SegmentDownloader DownloadSegment SegmentPlan SegmentFileWriter; do
    git show "$baseline:app/src/main/java/com/elewashy/nexa/feature/downloads/data/engine/$name.kt" \
        | python3 -c 'import sys; text=sys.stdin.read().replace("package com.elewashy.nexa.feature.downloads.data.engine", "package com.elewashy.nexa.downloadbenchmark.legacy\n\nimport com.elewashy.nexa.feature.downloads.data.engine.*"); sys.stdout.write(text)' \
        > "$source_dir/legacy/$name.kt"
done
cp scripts/download-benchmark/DownloadBenchmark.kt "$source_dir/"
./gradlew :app:testDebugUnitTest --tests 'com.elewashy.nexa.downloadbenchmark.DownloadBenchmark' "$@"
echo "Results: ${NEXA_BENCH_OUTPUT:-/tmp/nexa-download-benchmark.csv}"
