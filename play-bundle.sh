#!/bin/bash
# Build the Google Play upload file (.aab) for the version in android-app/version.properties.
# Upload it in Play Console > Test and release. The Play build has no GitHub self-updater.
#
#   ./play-bundle.sh
set -e
cd "$(dirname "$0")/android-app"
V=$(grep '^versionName=' version.properties | cut -d= -f2 | tr -d '[:space:]')
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
./gradlew --console=plain -q testPlayReleaseUnitTest bundlePlayRelease
OUT="../KhmerDubber-play-v$V.aab"
cp app/build/outputs/bundle/playRelease/app-play-release.aab "$OUT"
echo "Ready to upload: $(cd .. && pwd)/KhmerDubber-play-v$V.aab"
