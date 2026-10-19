# Publishing Surge Media — GitHub · GitVerse · RuStore

Everything in this repo is already release-shaped: version sync (`Version.APP` =
`versionName` = 0.2.0), env-driven release signing, CI building and testing the Android
app, MIT `LICENSE`, and a bilingual privacy policy at [`docs/PRIVACY.md`](PRIVACY.md).
This document is the step-by-step plan for the three publishing targets plus the
per-release checklist.

**Before any public link: replace the placeholder contact (`surge7@example.com`) in
`docs/PRIVACY.md` with a real email.**

## 0. One-time local setup

Create the release keystore (keep it out of git — `*.jks` is ignored):

    keytool -genkeypair -v -keystore surgemedia-release.jks -alias surgemedia \
      -keyalg RSA -keysize 4096 -validity 10000

Build a store-signed release APK:

    export SURGEMUSIC_KEYSTORE_PATH=$PWD/surgemedia-release.jks
    export SURGEMUSIC_KEYSTORE_PASSWORD=…
    export SURGEMUSIC_KEY_ALIAS=surgemedia
    export SURGEMUSIC_KEY_PASSWORD=…
    ./gradlew :composeApp:assembleRelease

Without those env vars `assembleRelease` still succeeds but falls back to the **debug**
keystore — fine for testing, never for stores. Verify who signed before uploading:

    $ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
      composeApp/build/outputs/apk/release/composeApp-release.apk

APK path: `composeApp/build/outputs/apk/release/composeApp-release.apk`
(~70 MB; contains `arm64-v8a` + `x86_64` native libs — no `armeabi-v7a`).

## 1. GitHub

1. Create the repo and push (adjust owner/name):

       gh repo create surge7/SurgeMedia --public --source=. --remote=origin --push
       gh repo edit --description "Kotlin Multiplatform torrent music/video downloader (Android + desktop)" \
         --add-topic kotlin --add-topic compose-multiplatform --add-topic torrent --add-topic android

2. Tag and cut the first release:

       git tag -a v0.2.0 -m "Surge Media 0.2.0"
       git push origin main --tags
       gh release create v0.2.0 --title "Surge Media 0.2.0" --notes-file CHANGELOG.md \
         composeApp/build/outputs/apk/release/composeApp-release.apk

   Attach the APK's SHA-256 to the release notes (`shasum -a 256 <apk>`).

3. Privacy-policy URL for listings: `https://github.com/surge7/SurgeMedia/blob/main/docs/PRIVACY.md`.

4. Protect `main` (require the `ci` workflow) once team flow matters. CI already runs on
   every push/PR: `:core:jvmTest`, `:cli:build`, `:composeApp:assembleDebug`,
   `:composeApp:testDebugUnitTest`, `:composeApp:jvmTest`.

5. Optional later: a `release` workflow that builds the signed APK from repo secrets
   (`SURGEMUSIC_KEYSTORE_BASE64` + the four vars) instead of a local keystore.

## 2. GitVerse (code mirror)

GitVerse is code hosting only — no app store attached — so this is a repo mirror with a
Russian-facing description.

1. Sign up at `gitverse.ru`, create an **empty** repo (no README init).
2. Mirror-push:

       git remote add gitverse https://gitverse.ru/<user>/SurgeMedia.git
       git push gitverse main --tags

3. In the repo settings set the description in Russian (e.g. «BitTorrent-клиент и поиск
   по настраиваемым зеркалам — Kotlin Multiplatform, Android и десктоп») and make
   `main` the default branch.
4. Sync after every release: `git push origin main --tags && git push gitverse main --tags`
   (or store a GitVerse access token and add the push to a small `scripts/release.sh`).
   GitVerse authenticates over HTTPS with personal access tokens — add one when asked,
   never commit it.

## 3. RuStore (Android app)

1. **Account** — register at `console.rustore.ru`. RuStore verifies the developer:
   legal entity / ИП, or self-employed (самозанятый) individuals. Have passport/registration
   docs ready; verification usually takes a few days.
2. **Listing prep** (per app):
   - Store-signed **APK** (RuStore does not re-sign; the keystore from §0 is the identity
     forever — back it up). `versionCode` must increase with every upload.
   - Name: «Surge Media». Short + full description **in Russian**; category
     «Медиа» / «Музыка и аудио».
   - Screenshots (phone), feature graphic, 512×512 icon.
   - Privacy policy URL → the GitHub link from §1.3 (fix the contact email first).
   - Age rating + content questionnaires in the console.
3. **Moderation pointers specific to this app**:
   - Describe it as a *universal BitTorrent client with configurable mirror search*.
     Do **not** mention rutracker or any specific tracker in the listing text or
     screenshots — store moderation is sensitive to pointers to pirated-content
     sources. The in-app UI naming rutracker is acceptable; the listing shouldn't.
   - Keep the «ships no indexes or content» framing from the README legal section.
   - Declare permission usage: `FOREGROUND_SERVICE_DATA_SYNC` (download service),
     `POST_NOTIFICATIONS` (progress), `READ_MEDIA_*` (showing the user's own downloads).
4. **Upload** — console → «Создать приложение» → APK upload → metadata → чек-лист →
   submit for moderation. First moderation typically 1–7 days.
5. **Updates** — bump `versionCode` (+ `versionName` if user-facing), same keystore,
   upload; users get auto-updates via the RuStore app (no extra SDK required; the
   optional RuStore updates SDK can come later).
6. **Known-limitation note for the listing**: Android 15+ caps `dataSync` foreground
   services at ~6 hours, so very large single-session downloads may pause; resuming
   continues them.

## 4. Per-release checklist

1. Bump `versionCode` (+1) and `versionName` in `composeApp/build.gradle.kts`, and
   `Version.APP` in `core/.../Version.kt`; add a `CHANGELOG.md` entry.
2. `./gradlew :core:jvmTest :cli:build :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:testDebugUnitTest :composeApp:jvmTest`
3. `git commit`, `git tag -a vX.Y.Z -m "Surge Media X.Y.Z"`.
4. Build the signed APK (§0), record SHA-256, GitHub Release with the APK (§1.2).
5. `git push origin main --tags && git push gitverse main --tags`.
6. RuStore console: upload the new APK, changelog in Russian, submit.

## 5. Caveats / future work

- **APK only** right now; `./gradlew :composeApp:bundleRelease` produces an AAB if a
  store that consumes bundles is added later.
- **No `armeabi-v7a`** native lib — RuStore's device filter handles this automatically;
  add the jlibtorrent artifact if 32-bit coverage is ever needed.
- **R8/minify off** by design (jlibtorrent JNI keep-rules unverified) — revisit if APK
  size matters; then also revisit `lint { checkReleaseBuilds = false }`.
- **macOS desktop DMG** exists (`:composeApp:packageDmg`) but is unsigned/un-notarized —
  distribution outside `adb`-style sideloading needs an Apple Developer ID and
  notarization, out of scope here.
- The in-app language override does not reach Android notification strings (they follow
  the OS locale by design); revisit with per-app language APIs if it bothers users.
