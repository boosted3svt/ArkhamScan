#!/usr/bin/env bash
set -euo pipefail
cd /workspace/ArkhamScan
source tools/cloud-env.sh
# JDK/SDK and verified Gradle distribution are retained in the published snapshot.
# No tracked source, dependency declarations, or lockfiles are rewritten.
test -x "$JAVA_HOME/bin/javac"
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

python3 -m unittest discover -s tools/tests -v
