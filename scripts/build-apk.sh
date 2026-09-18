#!/usr/bin/env bash
#
# Builds the Perchance Android app into an installable APK, installing the whole
# toolchain (JDK 17, Android SDK) itself.
#
# Works on Debian/Ubuntu (including Google Colab) and on Termux for Android.
#
#   bash build-apk.sh [project-url]
#
# If it is run from the project directory (the one with gradlew in it) the local
# sources are built. Otherwise the sources are fetched from the given URL (or
# from $PROJECT_ZIP_URL) into $PERCHANCE_BUILD_DIR (default ~/perchance-build).
# The URL may be the project zip, a repository archive (.zip/.tar.gz - the
# enclosing <repo>-<branch>/ folder is flattened automatically), or a git clone
# URL such as https://github.com/you/repo.git
#
# Result: app/build/outputs/apk/debug/app-debug.apk

set -euo pipefail

ZIP_URL="${1:-${PROJECT_ZIP_URL:-https://user.uploads.dev/file/12c1e09b7e6e3f025e2942bcdb27dd3c.zip}}"
BUILD_ROOT="${PERCHANCE_BUILD_DIR:-$HOME/perchance-build}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
PLATFORM="android-35"
BUILD_TOOLS="35.0.0"

note() { printf '\n==> %s\n' "$*"; }

on_termux() {
  case "$(uname -o 2>/dev/null)" in
    Android) return 0 ;;
    *) return 1 ;;
  esac
}

note "Installing build dependencies"
if on_termux; then
  pkg update -y
  pkg install -y openjdk-17 gradle aapt2 unzip curl
else
  if command -v apt-get > /dev/null 2>&1; then
    SUDO=""
    if [ "$(id -u)" -ne 0 ]; then
      SUDO="sudo"
    fi
    $SUDO apt-get update -qq
    $SUDO apt-get install -y -qq openjdk-17-jdk-headless unzip curl
  fi
fi

jdk_major() {
  "$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p'
}

# The Android Gradle Plugin needs JDK 17+, so prefer a 17 install over whatever
# JAVA_HOME happens to point at - Google Colab, for example, ships JDK 11.
find_jdk17() {
  local candidate major
  for candidate in \
    "${JAVA_HOME:-}" \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /usr/lib/jvm/java-1.17.0-openjdk-amd64 \
    "${PREFIX:-/nonexistent}/lib/jvm/java-17-openjdk"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ]; then
      major="$(jdk_major "$candidate")"
      if [ -n "$major" ] && [ "$major" -ge 17 ]; then
        JAVA_HOME="$candidate"
        return 0
      fi
    fi
  done
  return 1
}

if ! find_jdk17; then
  JAVAC_PATH="$(command -v javac || true)"
  if [ -n "$JAVAC_PATH" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")"
  fi
fi
if ! find_jdk17; then
  echo "The Android Gradle Plugin needs JDK 17 or newer, but none was found." >&2
  echo "JAVA_HOME=${JAVA_HOME:-unset}, javac on PATH: $(javac -version 2>&1 || echo none)" >&2
  echo "Install it (e.g. apt-get install openjdk-17-jdk-headless) and run this script again." >&2
  exit 1
fi
export JAVA_HOME
note "JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/javac" -version 2>&1))"

if [ -f ./gradlew ] && [ -f ./settings.gradle.kts ]; then
  PROJECT_DIR="$PWD"
elif [ -n "$ZIP_URL" ]; then
  note "Fetching the app source from $ZIP_URL"
  mkdir -p "$BUILD_ROOT"
  cd "$BUILD_ROOT"
  rm -rf project
  case "$ZIP_URL" in
    *.git|git@*|ssh://*|git+*)
      git clone --depth 1 "${ZIP_URL#git+}" project ;;
    *)
      mkdir project
      cd project
      curl -fsSL -o app-download "$ZIP_URL"
      if tar -tf app-download > /dev/null 2>&1; then
        tar -xaf app-download
      else
        unzip -q -o app-download
      fi
      rm -f app-download
      # Repository archives wrap everything in one top-level folder.
      if [ ! -f ./gradlew ]; then
        INNER=""
        for d in */; do INNER="${d%/}"; break; done
        if [ -n "$INNER" ] && [ -f "$INNER/gradlew" ]; then
          shopt -s dotglob
          mv "$INNER"/* .
          rmdir "$INNER"
          shopt -u dotglob
        fi
      fi
      cd .. ;;
  esac
  rm -rf project/.git
  cd project
  PROJECT_DIR="$PWD"
else
  echo "No gradlew in $PWD and no project source URL given. Pass one as the first argument." >&2
  exit 1
fi
cd "$PROJECT_DIR"
chmod +x ./gradlew

if [ ! -d "$SDK_ROOT/cmdline-tools/latest" ]; then
  note "Installing the Android SDK command-line tools"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  TOOLS_TMP="$(mktemp -d)"
  curl -fsSL -o "$TOOLS_TMP/tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$TOOLS_TMP/tools.zip" -d "$TOOLS_TMP"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$TOOLS_TMP/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

note "Accepting SDK licences"
yes | sdkmanager --sdk_root="$SDK_ROOT" --licenses > /dev/null || true

note "Installing platforms;$PLATFORM and build-tools;$BUILD_TOOLS"
sdkmanager --sdk_root="$SDK_ROOT" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" "platform-tools"

echo "sdk.dir=$SDK_ROOT" > local.properties

GRADLE_ARGS=(assembleDebug --no-daemon --stacktrace)
if on_termux; then
  # The aapt2 shipped in build-tools is an x86_64 binary; Termux provides a
  # native one that also runs on ARM phones.
  if [ -x "${PREFIX:-/nonexistent}/bin/aapt2" ]; then
    GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=${PREFIX}/bin/aapt2")
  fi
fi

note "Building the APK (this takes a few minutes)"
./gradlew "${GRADLE_ARGS[@]}"

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
note "Built: $APK_PATH"

if on_termux; then
  DEST="$HOME/storage/downloads/Perchance.apk"
  if cp "$APK_PATH" "$DEST" 2>/dev/null; then
    note "Copied to $DEST - open it from your Downloads app to install"
  else
    note "Run 'termux-setup-storage' once, then copy the APK from $APK_PATH"
  fi
fi
