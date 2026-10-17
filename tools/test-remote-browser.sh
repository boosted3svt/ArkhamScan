#!/usr/bin/env bash
set -euo pipefail
cd /workspace/ArkhamScan
source tools/cloud-env.sh
harness_dir=$(mktemp -d /tmp/arkham-browser.XXXXXX)
trap 'rm -rf "$harness_dir"' EXIT
./gradlew :app:testDebugUnitTest --tests com.arkhamscan.RemoteBrowserHarnessTest -PbrowserHarnessDir="$harness_dir" > "$harness_dir/gradle.log" 2>&1 &
gradle_pid=$!
node tools/test-remote-browser.cjs "$harness_dir" || { cat "$harness_dir/gradle.log"; wait "$gradle_pid" || true; exit 1; }
wait "$gradle_pid" || { cat "$harness_dir/gradle.log"; exit 1; }
cat "$harness_dir/gradle.log"
