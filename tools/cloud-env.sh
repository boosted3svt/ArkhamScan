# Source from the existing isolated checkout; no worktree is needed.
export JAVA_HOME=/workspace/.tools/jdk-21.0.12.1
export ANDROID_HOME=/workspace/.tools/android-sdk
export ANDROID_USER_HOME=/workspace/.tools/android-user
export GRADLE_USER_HOME=/workspace/.tools/gradle-home
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
