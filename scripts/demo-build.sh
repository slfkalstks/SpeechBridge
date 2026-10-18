#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
export GRADLE_USER_HOME="$project_dir/.runtime/gradle-home"
export KONAN_DATA_DIR="$project_dir/.runtime/konan"
export TMPDIR="$project_dir/.runtime/tmp"
mkdir -p "$TMPDIR"
native_dir="$KONAN_DATA_DIR/kotlin-native-prebuilt-macos-aarch64-2.4.20"
test -d "$native_dir" || { echo 'Project Kotlin/Native runtime is missing.'; exit 1; }
node server/demo.mjs start
./gradlew :androidApp:assembleDebug -Pspeechbridge.localTls=true \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.native.home="$native_dir" \
  --no-configuration-cache --no-daemon --console=plain
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.configuration-cache=false -Dorg.gradle.daemon=false -Dorg.gradle.project.kotlin.native.home=$native_dir -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process -Dorg.gradle.project.speechbridge.localTls=true"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .runtime/xcode-derived build CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=
node scripts/demo-artifacts.mjs package
