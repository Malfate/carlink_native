# Global Codex Context

Before doing any work, read and follow `/Users/krushin/.claude/CLAUDE.md` as my persistent user/project preferences.

## Vehicle Context

- My vehicle is a 2025 GMC Sierra EV.

## Android / Play Store Context

- We have access to an Android developer account and can deploy this app to the Google Play Store when explicitly requested.
- Do not publish, promote, or upload Play Store releases unless the user explicitly asks for that deployment step.
- When using browser/Computer Use for Play Console or Google Cloud Console, use the work Chrome profile (`rushin.ai` / green tabs).
- Play Console uploads must go to the **Android Automotive OS** dedicated internal testing track, not the generic internal testing track. A bundle requiring `android.hardware.type.automotive` will be rejected on non-automotive tracks with an error like "APKs and bundles must not require following features: android.hardware.type.automotive."
- The AAOS internal testing API track name for Gradle Play Publisher is `automotive:qa`.
- Service-account JSON credentials must stay outside git. Preferred local path for automation is `~/.config/carlink/play-service-account.json`, or set `PLAY_SERVICE_ACCOUNT_JSON` / `-PplayServiceAccountCredentials=...`.
- Creating Google Cloud service accounts, JSON keys, or Play Console permissions is persistent access; ask for explicit confirmation immediately before those steps.

## Development And Test Workflow

- Main Android app package: `com.krushin.carplay`; target vehicle/test environment is Android Automotive OS.
- Build variants are intentionally split:
  - `sideloadDebug` / `sideloadRelease`: APK builds for direct install and in-vehicle testing.
  - `playRelease`: signed AAB for Google Play. There is no useful `playDebug` variant.
- Common verification commands:
  - `./gradlew :app:compileSideloadDebugKotlin` for fast Kotlin compile checks.
  - `./gradlew :app:assembleSideloadDebug` for a sideload APK.
  - `./gradlew :app:bundlePlayRelease` for a signed Play AAB.
  - `./gradlew help --task :app:publishPlayReleaseBundle` to verify Play Publisher CLI wiring.
- Expected release artifact path: `app/build/outputs/bundle/playRelease/app-play-release.aab`.
- Gradle may need access to the local `~/.gradle` wrapper/cache outside the workspace sandbox; request escalation when the wrapper lock/cache is blocked.
- Existing Media3 `@OptIn` warnings during builds are known and not currently release-blocking.
- When bumping Play versions, update both `versionCode` in `app/build.gradle.kts` and `documents/revisions.txt`.
- Keep runtime changes separate from release-only version bumps when possible so connection fixes are easy to review.
- Before using CLI Play upload, confirm the release is targeted as draft unless the user explicitly asks to publish/promote/roll out.

## Current Release Automation Notes

- Gradle Play Publisher is the preferred CLI path for repeatable uploads.
- Intended publish command after credentials are configured:
  - `./gradlew :app:publishPlayReleaseBundle --track automotive:qa --release-status draft --release-name "1.0.0 (<versionCode>)"`
- For the current project configuration, the default Play Publisher config should use:
  - track: `automotive:qa`
  - release status: `DRAFT`
  - service-account credentials outside repo
- Google's current API setup flow is: create/choose a Google Cloud project, enable Google Play Developer API, create a service account, invite that service-account email in Play Console Users & permissions, then grant only the permissions needed to upload/manage releases for this app.

## Carlinkit Manufacturer Reference Context

- This repo does **not** currently contain the raw manufacturer APKs or a full checked-in decompiled source tree.
- It does contain distilled reverse-engineering notes derived from manufacturer/reference apps and firmware analysis, especially AutoKit v2025.03.19.1126, PhoneMirrorBox r5889, and CPC200-CCPA firmware binaries.
- For future compatibility or newer CarlinKit hardware work, check these docs first:
  - `documents/reference/adapter/RE_Documention/02_Protocol_Reference/usb_protocol.md`
  - `documents/reference/adapter/RE_Documention/02_Protocol_Reference/video_protocol.md`
  - `documents/reference/adapter/RE_Documention/02_Protocol_Reference/audio_protocol.md`
  - `documents/reference/adapter/RE_Documention/04_Implementation/host_app_guide.md`
  - `documents/reference/adapter/RE_Documention/05_Security_Analysis/crypto_stack.md`
- Treat these docs as the local source of truth for known AutoKit behavior: init sequence (`0xA0`, `0xF0`, Open, BoxSettings), USB bulk protocol framing, heartbeat behavior, touch packet semantics, audio/video payload formats, resolution tier logic, and OEM quirks.
- For newer hardware such as CarlinKit 5.0 / 2air, do not assume compatibility from brand alone. First verify USB VID/PID, descriptor/interface shape, bulk IN/OUT endpoints, and whether the device responds to the CPC200/AutoKit initialization sequence.
