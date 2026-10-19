# BolPilot Android project

This is a source-code Android Studio project for BolPilot, keeping the existing application ID `in.examdarpan.vaanix` so the existing Firebase Android registration can be reused if it matches.

## What is implemented in this source build

- Dark navy / cyan BolPilot interface and adaptive launcher icon.
- Tap-to-talk speech recognition using Android's speech service.
- Spoken responses using Android TextToSpeech.
- Commands: current time/date, battery percentage, Google web search, open YouTube/WhatsApp/Chrome/Maps/Camera/Gmail/Instagram/Telegram/Facebook, open Android Settings, and open the dialer after a confirmation prompt.
- Optional voice-triggered screen lock after the user explicitly enables Android Device Administrator permission. This can lock the screen but cannot bypass PIN, password, or biometric unlock.
- Hindi (India) and English (US) speech selection.
- Local command history (up to 50 items) and settings stored on-device.
- Permission and help screens.
- Google Sign-In + Firebase Auth code, guarded so the project can still sync/build before your own Firebase configuration is added.

## Important limits

- This is a real starter implementation, not a verified Play Store release. It has not been built on your local Android SDK in this environment.
- Speech recognition availability and language support depend on the Android device and installed speech service.
- Background always-on hotword detection is intentionally not enabled; tap-to-talk is used to respect Android background microphone restrictions.
- Voice screen lock requires the user to explicitly approve Android's device administrator prompt. The app does not request remote wipe or other device-admin policies.
- Premium screen is a truthful placeholder until subscription products and billing verification are configured. It does not pretend to sell subscriptions.
- Firebase Google Sign-In requires your own `google-services.json`, Firebase Authentication Google provider, and OAuth Web Client ID. Do not upload private service-account keys.

## Open and build

1. Extract the ZIP.
2. Open the `BolPilot-Android` folder in Android Studio.
3. Let Gradle sync finish. Use JDK 17 and install Android SDK Platform 35 if prompted.
4. Run the app on a real Android phone to test speech recognition and permissions.

## Connect Firebase

1. Open Firebase Console project `vaanix-23889` (or the project you intend to use).
2. Ensure its Android app registration has package `in.examdarpan.vaanix`.
3. Download the `google-services.json` for that exact Android app and place it at `app/google-services.json`.
4. In Firebase Authentication, enable Google as a sign-in provider.
5. Verify the SHA-1 and SHA-256 fingerprints for the signing key used to run the app are registered in Firebase. For Play releases, also register the Play App Signing certificate fingerprints.
6. Sync Gradle, rebuild, and test Google sign-in on a device.

If the Web Client ID error remains, inspect the generated `default_web_client_id` resource and the OAuth client in Google Cloud/Firebase. Do not paste secret credentials into source code.

## Build APK

- On GitHub, open **Actions > Android Build**. A successful run uploads `BolPilot-debug-apk` as a downloadable artifact for 14 days.
- Locally in Android Studio: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
- The automated build validates compilation only; real-device testing is still required for speech recognition, screen lock, and Google sign-in.

## GitHub Actions build

The workflow runs on pushes to `main` and `chore/**`, pull requests to `main`, and manual dispatch. It uses JDK 17 and runs `./gradlew --no-daemon assembleDebug`.
