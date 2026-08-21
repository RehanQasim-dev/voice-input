# Voice Pill Keyboard (FUTO voice-input fork)

## Build

System default JDK is too new for Gradle 8.11 — always build with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleStandaloneDebug
```

- SDK location is set in `local.properties` (gitignored) → `/home/rehan-10xe/.bubblewrap/android_sdk`
- APK output: `app/build/outputs/apk/standalone/debug/app-standalone-debug.apk`
- The `dep/futopay` git submodule must be initialized (`git submodule update --init`)

## After every APK build: install to Waydroid

Always deploy the freshly built APK to the running Waydroid instance:

```bash
waydroid app install app/build/outputs/apk/standalone/debug/app-standalone-debug.apk
waydroid app list | grep -A1 "FUTO Voice Input"   # verify install
```

## Release

Releases go to the user's fork `RehanQasim-dev/voice-input` via gh CLI:

```bash
git push fork master
gh release upload <tag> app/build/outputs/apk/standalone/debug/app-standalone-debug.apk --repo RehanQasim-dev/voice-input --clobber
```

Remote `fork` = `git@github.com:RehanQasim-dev/voice-input.git` (`origin` points at upstream futo-org, do not push there).
