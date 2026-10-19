# Publishing VisionMirror on Google Play

## What to upload
- **App bundle (.aab)**: `android/app/build/outputs/bundle/playRelease/app-play-release.aab`. Play requires a bundle for new apps.
- **APK for the website**: `android/app/build/outputs/apk/direct/release/app-direct-release.apk` (already served from the download site).
- Two flavours, same code: **play** (no SEND_SMS; the emergency text opens in the messaging app and one tap sends it, because Google Play only allows SEND_SMS for default messaging apps) and **direct** (website; sends the text by itself).

Build: `cd android && ./gradlew :app:bundlePlayRelease :app:assembleDirectRelease`. Signed with `release.jks` (the upload key). Back up `release.jks` and `keystore.properties` somewhere safe; enrol in **Play App Signing** when creating the app.

## Store listing (copy)
- **App name:** VisionMirror: Voice Assistant
- **Short description (80):** Say Adanna or Bola. A Nigerian-voiced assistant for blind and low-vision people.
- **Full description:** VisionMirror is a voice assistant for blind and low-vision people. Say "Adanna" or "Bola" and ask. It reads labels and signs, describes what is in front of you, tells you how you look, answers questions, tells you where you are, and can message your location to someone you trust. Guide mode watches ahead and tells you about people and objects, with vibration that quickens as something gets close. Natural Nigerian English voices, with Hausa, Yoruba and Igbo. Hands-free. Photos are never stored. VisionMirror is an extra pair of eyes, not a replacement for a cane or a guide: it cannot see steps, holes or glass.
- **Category:** Tools (or Health & Fitness > Accessibility as offered). **Contact email:** required by Play; use yours.
- **Graphics (in /store):** `app-icon-512.png` (512x512), `feature-graphic.png` (1024x500). **Phone screenshots (2 to 8) are still needed**: take them from the phone with the Play build installed (home, a described photo, Guide mode, settings).
- **Privacy policy URL:** https://visionmirror-app.vercel.app/privacy
- **Account deletion URL (Data safety):** https://visionmirror-app.vercel.app/account-deletion

## Play Console forms
- **Content rating:** answer the questionnaire honestly (no violence, no user-generated content): expect Everyone.
- **Target audience:** 13 and over (the privacy policy says so).
- **Ads:** none. **App access:** all features work without an account (guest); no login needed for review.
- **Data safety** (all encrypted in transit; deletable by the user):
  - Email address, name: collected, account management, optional.
  - Photos: collected, processed ephemerally (not stored), app functionality.
  - Audio: not collected or stored (speech recognition is the phone's own service); question text is sent to answer it.
  - Approximate and precise location: collected only on request, sent to the user's chosen contact, not stored by us.
  - Contacts: read on device to pick the emergency contact, not uploaded.
  - Not shared with third parties for their own use; processors only (AI, speech, translation).
- **Permissions declaration:** CAMERA and RECORD_AUDIO (core), ACCESS_FINE/COARSE_LOCATION (where am I, SOS), READ_CONTACTS (choose emergency contact by name). SEND_SMS is **not** in the Play build.
- **Accessibility:** the app is not an AccessibilityService; no declaration needed.

## Before the first release
1. Create the app in Play Console, upload the .aab to **Internal testing** first and install from the test link on a real phone.
2. Check wake word, Guide mode, SOS message and sign-in on that build.
3. New personal developer accounts must run a **closed test with at least 12 testers for 14 days** before production access.
4. Raise `versionCode` in `android/app/build.gradle.kts` for every upload.
