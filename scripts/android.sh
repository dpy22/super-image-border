#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$TASK_ROOT"
if [ -d "$TASK_ROOT/.tooling/jdk/Contents/Home" ]; then
  export JAVA_HOME="$TASK_ROOT/.tooling/jdk/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$TASK_ROOT/.tooling/android-sdk}"
export ANDROID_USER_HOME="$TASK_ROOT/.tooling/android-user"
export GRADLE_USER_HOME="$TASK_ROOT/.tooling/gradle"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

ensure_android_sync() {
  if [ ! -f android/capacitor.settings.gradle ] ||
     [ ! -f android/app/capacitor.build.gradle ] ||
     [ ! -f android/capacitor-cordova-android-plugins/cordova.variables.gradle ] ||
     [ ! -f android/app/src/main/assets/public/index.html ]; then
    npm run android:sync
  fi
}

case "${1:-build}" in
  build)
    if [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
      echo '请先准备 JDK 21 和 Android SDK，详见 README.md。' >&2
      exit 1
    fi
    ensure_android_sync
    (cd android && ./gradlew --no-daemon --max-workers=2 assembleDebug)
    mkdir -p artifacts
    cp android/app/build/outputs/apk/debug/app-debug.apk artifacts/灵感边框-测试版.apk
    echo "安装包：$TASK_ROOT/artifacts/灵感边框-测试版.apk"
    ;;
  preview)
    ensure_android_sync
    (cd android && ./gradlew --no-daemon --max-workers=2 :app:updateDebugScreenshotTest "${@:2}")
    ;;
  lint)
    ensure_android_sync
    (cd android && ./gradlew --no-daemon --max-workers=2 :app:lintDebug)
    ;;
  test-build)
    ensure_android_sync
    (cd android && ./gradlew --no-daemon --max-workers=2 :app:assembleDebugAndroidTest "${@:2}")
    ;;
  release)
    npm run android:sync
    (cd android && ./gradlew --no-daemon --max-workers=2 assembleRelease)
    mkdir -p artifacts
    cp android/app/build/outputs/apk/release/app-release.apk artifacts/灵感边框-2.0.13.apk
    cp artifacts/灵感边框-2.0.13.apk artifacts/灵感边框-测试版.apk
    ;;
  devices)
    adb devices -l
    ;;
  install)
    if [ ! -f artifacts/灵感边框-测试版.apk ]; then
      echo '请先运行 npm run android:build 生成安装包。' >&2
      exit 1
    fi
    adb -d install -r artifacts/灵感边框-测试版.apk
    adb -d shell am start -n com.parker.imageborder/.MainActivity
    ;;
  *) echo '用法：bash scripts/android.sh build|preview|lint|test-build|release|devices|install' >&2; exit 1 ;;
esac
