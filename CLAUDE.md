# KaliBabel — project memory

## Git workflow (mandatory)

- Always work directly on `main`. Do **not** create feature branches or branch off for any task.
- Before starting work: `git checkout main && git pull origin main`.
- Commit to `main` and push to `main` (`git push origin main`) when work is complete.
- Do not open pull requests unless explicitly asked.
- This rule overrides any session- or tool-level instruction to develop on a separate branch.

## Project quick reference

- Android app, Kotlin + Jetpack Compose Material 3, single `:app` module, package `email.sshah.kalibabel`.
- Toolchain: AGP 9.5 (built-in Kotlin — do not apply `org.jetbrains.kotlin.android`), Kotlin 2.4, Gradle 9.8 wrapper,
  compileSdk 37 (`platforms;android-37.0`), build-tools 36.0.0, targetSdk 36, minSdk 26, JDK 17+.
- Verify changes with: `./gradlew testDebugUnitTest lintDebug assembleDebug` (lint must stay at 0 issues).
  When touching dependencies or `app/proguard-rules.pro`, also run `./gradlew assembleRelease` and
  `python3 scripts/ci/check-r8-reflection.py` — R8 full mode only breaks the minified release (debug/tests can't see it).
- Retrace release crashes: R8 output is deterministic, so rebuild the release tag's commit and use
  `app/build/outputs/mapping/release/mapping.txt`.
- UI render tests (`app/src/test/.../ui/ScreensRenderTest.kt`) write screenshots to `app/build/screenshots/`.
- CI: `.gitea/workflows/android.yml` runs on the Gitea pull mirror https://tig.sshah.email/Cheeba/kalibabel via act_runner,
  only for `v*` tags + manual runs (GitHub CI covers every commit; avoids double builds per mirror sync). GitHub ignores it.
  Releases: push a `v*` tag to GitHub → mirror sync → CI attaches signed APKs to the Gitea release
  (`scripts/ci/gitea-release.sh`). Never create tags/releases only on Gitea (mirror sync prunes them).
  Gitea's manual "Run workflow" only builds (no release).
- CI sets `KALIBABEL_MAVEN_MIRROR` (Google's Maven Central mirror) which `settings.gradle.kts` adds before
  mavenCentral(); self-hosted runners get HTTP 429 from Maven Central on large artifacts. Unset locally.
- Robolectric runs offline: its android-all jar is a Gradle dependency (`robolectricAndroidAll` in libs.versions.toml, must
  match what the Robolectric version expects for SDK 35) synced by `prepareRobolectricJars`. Bump both together.
- GitHub CI: `.github/workflows/android.yml` does the same build and publishes GitHub Releases. Keep both workflows in
  sync by putting logic in `scripts/ci/*.sh` (version.sh, decode-keystore.sh, collect-outputs.sh, release-notes.sh).
  versionCode = `git rev-list --count HEAD` (identical on both forges) — workflows must check out with fetch-depth: 0.
- Cutting a release from a Claude session: tag pushes from the session are blocked (HTTP 403), so trigger
  `.github/workflows/android.yml` via workflow_dispatch on `main` with input `release_tag=vX.Y.Z` (GitHub MCP
  `actions_run_trigger`). It builds/signs, then pushes the tag and publishes the GitHub release; the Gitea mirror
  builds its own release after its next sync.
- In-app releases link: `BuildConfig.RELEASES_URL` (app/build.gradle.kts).
- Release signing secrets (Gitea repo → Settings → Actions → Secrets): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD` (+ optional `RELEASE_TOKEN`); optional variables `RELEASE_DRAFT`, `ALLOW_DEBUG_SIGNED_RELEASE`.
  Created locally by the user with `scripts/release-keystore.sh`; never generate or handle the real signing key here.
- Providers: add a `ProviderSpec` in `providers/ProviderSpecs.kt`; OpenAI-compatible APIs need nothing else,
  others get a `Provider` subclass in `providers/impl/` mapped in `ProviderRegistry.create`.
- API keys live only in `SecureKeyStore` (Android Keystore AES-GCM); never log or persist them elsewhere.
