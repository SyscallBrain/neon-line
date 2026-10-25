#!/usr/bin/env bash
# Builds the signed release APK for a GitHub release: checks the signing setup, builds with R8,
# verifies the signature and writes the APK, its SHA-256 and the certificate fingerprint to
# build/release/. Publishes nothing. Signing properties come from ~/.gradle/gradle.properties
# (neonLine.release.storeFile, storePassword, keyAlias, keyPassword); they are never printed.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
# Fall back to the author's machine layout only when nothing is set and those paths exist.
if [[ -z "${JAVA_HOME:-}" && -d /opt/android-studio/jbr ]]; then export JAVA_HOME=/opt/android-studio/jbr; fi
if [[ -z "${ANDROID_HOME:-}" && -d /opt/android-sdk ]]; then export ANDROID_HOME=/opt/android-sdk; fi
# apksigner is a shell wrapper that runs `java` from PATH.
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

properties="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
for key in storeFile storePassword keyAlias keyPassword; do
    if ! grep -q "^neonLine\.release\.$key=" "$properties" 2>/dev/null; then
        printf 'Missing neonLine.release.%s in %s; see README "Signing a release".\n' "$key" "$properties" >&2
        exit 1
    fi
done
store=$(grep '^neonLine\.release\.storeFile=' "$properties" | head -1 | cut -d= -f2-)
if [[ ! -f "$store" ]]; then
    printf 'Keystore not found: %s\n' "$store" >&2
    exit 1
fi

apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [[ -z "$apksigner" ]]; then
    printf 'apksigner not found under %s/build-tools\n' "$ANDROID_HOME" >&2
    exit 1
fi

mkdir -p logs
result=0
./gradlew :app:assembleRelease --console=plain >logs/release.log 2>&1 || result=$?
./gradlew --stop >/dev/null 2>&1 || true
if (( result != 0 )); then
    tail -40 logs/release.log >&2
    printf 'Release build failed (exit %d); see logs/release.log\n' "$result" >&2
    exit "$result"
fi

apk=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$apk" ]]; then
    printf 'No signed APK at %s; check the signing properties.\n' "$apk" >&2
    exit 1
fi
"$apksigner" verify "$apk"

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
out=build/release
mkdir -p "$out"
target="$out/NeonLine-$version.apk"
cp "$apk" "$target"
apk_sha=$(sha256sum "$target" | cut -d' ' -f1)
cert_sha=$("$apksigner" verify --print-certs "$target" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
{
    printf 'NeonLine %s\n\n' "$version"
    printf 'APK SHA-256: %s\n' "$apk_sha"
    printf 'Signing certificate SHA-256: %s\n' "$cert_sha"
} >"$out/NeonLine-$version.sha256.txt"

printf '\nRelease APK: %s\n' "$target"
cat "$out/NeonLine-$version.sha256.txt"
printf '\nTo publish (after review):\n  gh release create v%s %s --title "NeonLine %s" --notes-file docs/release-notes/%s.md\n' \
    "$version" "$target" "$version" "$version"
