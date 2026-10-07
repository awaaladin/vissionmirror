# Building a signed release APK

## 1. Point the app at your backend

In `android/local.properties` (git-ignored):

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
API_BASE_URL=https://your-backend.example.com/
```

The trailing slash is required. A real phone cannot reach `10.0.2.2` (that address is only for the emulator): use your machine's LAN IP, or a deployed URL.

**Before shipping**, remove `android:usesCleartextTraffic="true"` from `AndroidManifest.xml` and use an `https://` URL. Cleartext is only there for the local dev backend.

## 2. Create a signing key (once)

```bash
keytool -genkeypair -v -keystore release.jks -alias visionmirror \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep `release.jks` and its passwords somewhere safe (a password manager). If you lose the key you cannot update the app. **Never commit it.**

Copy `keystore.properties.example` to `keystore.properties` and fill it in:

```properties
storeFile=release.jks
storePassword=...
keyAlias=visionmirror
keyPassword=...
```

Both `*.jks` and `keystore.properties` are git-ignored.

## 3. Build

```bash
cd android
./gradlew :app:testDebugUnitTest      # all unit tests should pass
./gradlew :app:assembleRelease        # R8-minified, resource-shrunk, signed if keystore.properties exists
```

Output: `app/build/outputs/apk/release/app-release.apk`

If `keystore.properties` is missing the APK is built **unsigned** (`app-release-unsigned.apk`) and will not install.

## 4. Install and smoke-test on a phone

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then walk the checklist in `README.md` ("Manual verification") and run `docs/demo-script.md` once end to end.

## 5. Baseline Profile (recommended before a public release)

`app/src/main/baseline-prof.txt` is a hand-written profile that already improves cold start. For a measured one, add a Macrobenchmark module with a `BaselineProfileRule` that launches the app, walks Onboarding to Mirror, and generates rules on a physical device (API 33+), then replace the file.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| App opens, "I can't reach the internet" | `API_BASE_URL` wrong, backend not running, or a phone using `10.0.2.2`. Open `<url>/v1/health` in the phone's browser. |
| No spoken output | No TTS engine installed, or media volume is zero. Settings > Accessibility > Text-to-speech. |
| "I need the microphone" | Microphone permission was denied. Phone Settings > Apps > VisionMirror > Permissions. |
| Gradle download timeouts | `networkTimeout` in `gradle/wrapper/gradle-wrapper.properties` is already 120 s; retry on a faster connection. |
