# KaliBabel

> *"The Babel fish is small, yellow, leech-like, and probably the oddest thing in the Universe…
> if you stick one in your ear, you can instantly understand anything said to you in any form of language."*

KaliBabel is an Android live interpreter. You speak, it listens (STT), translates and speaks the
translation aloud (TTS), aiming to keep the delay from the end of your sentence to the first
translated word as short as possible. It's bring-your-own-key: you pick a provider for each stage,
mixing cloud and on-device engines. The free on-device stack works out of the box with no key.

<p>
<img src="docs/screenshots/conversation.png" width="200" alt="Conversation">
<img src="docs/screenshots/face-to-face.png" width="200" alt="Face-to-face mode">
<img src="docs/screenshots/settings.png" width="200" alt="Settings">
<img src="docs/screenshots/command-code-zdr.png" width="200" alt="Command Code with ZDR toggle">
</p>

*(Screenshots are rendered by the Robolectric UI tests in CI.)*

## Download

Signed APKs are attached to each release, both on the Gitea mirror
(**https://tig.sshah.email/Cheeba/kalibabel/releases**) and on GitHub
(**https://github.com/sshahs/kalibabel/releases**). The files are built from the same commit and
signed with the same key, so you can download from either. Each release has these files:

| File | Install on |
|---|---|
| `KaliBabel-<version>-arm64-v8a-release.apk` | Almost every phone from the last ~8 years |
| `KaliBabel-<version>-armeabi-v7a-release.apk` | Older 32-bit phones |
| `KaliBabel-<version>-x86_64-release.apk` | Emulators and Chromebooks |
| `KaliBabel-<version>-universal-release.apk` | Any device (larger download) |
| `SHA256SUMS.txt` | Checksums: `sha256sum -c SHA256SUMS.txt` |

You can also open this page from the app, under **Settings → About → Releases & updates**.

## Features

- **Three mics.** One for each person's language, plus an **Auto** mic that detects which of the two
  languages is being spoken (from STT language detection, then on-device ML Kit language ID, then an
  LLM fallback that tags its reply with the language).
- **Listening modes.**
  - **Auto-stop** (stops when you pause)
  - **Interpreter** (keeps listening and translates sentence by sentence)
  - **Manual** (records until you tap, then translates everything at once)
- **Face-to-face layout.** The screen splits in two, with the top half rotated 180° for the person opposite.
- **Every stage can use a different provider.** Each one has a live **model picker** (fetched from the
  provider's API, searchable, with custom IDs allowed) and a **voice picker** for each language.
- **Accuracy features:**
  - The interpreter prompt is tuned for speech transcripts and refuses to answer questions, so it never
    replies to "what time is it?"
  - Recent conversation context is sent with each request
  - Formal or informal register
  - A glossary that also biases speech recognition (Deepgram keyterms, ElevenLabs keyterms, AssemblyAI
    keyterms_prompt, Whisper prompt)
  - Free-form extra instructions
- **Resilience.** If a cloud provider fails (no signal, rate limit), the turn falls back to on-device
  translation and the system voice.
- **Latency breakdown on every message:** mouth-to-ear time, STT, time to first token, translation
  total, and time to first audio.
- Text input, **"Translate with KaliBabel"** in the system text-selection menu, and the share sheet.
- Replay, copy and delete for each message. History is stored locally only.
- **Keys are encrypted** with an AES-256-GCM key held in the Android Keystore. The app is excluded from
  cloud backup and device transfer. Each key is only sent to the provider it belongs to.

## Providers

| Provider | STT | Translation | TTS | Notes |
|---|:-:|:-:|:-:|---|
| **On-device (Android)** | ✅ live partials | ✅ ML Kit, offline | ✅ system TTS engines | No key. Language packs can be downloaded for offline use |
| **OpenAI** | ✅ gpt-4o(-mini)-transcribe, whisper-1 | ✅ | ✅ gpt-4o-mini-tts, streams PCM | |
| **Anthropic** | | ✅ Claude (Haiku default) | | |
| **Google Gemini** | ✅ audio understanding | ✅ thinking disabled for latency | ✅ native TTS, 30 voices | |
| **ElevenLabs** | ✅ Scribe v2 batch + **Scribe v2 Realtime** (WebSocket) | | ✅ Flash v2.5 / Turbo / Multilingual / v3, streams PCM | Live model and voice catalogs, data-residency region, **zero-retention toggle** (`enable_logging=false`, Enterprise only) |
| **Command Code** | | ✅ open, OpenAI-format and Claude models | | Claude models are routed to `/messages` automatically. **ZDR toggle** (`x-cmd-zdr: 1`) |
| **Ollama Cloud** | | ✅ `ollama.com/api/chat` | | Native API, thinking disabled |
| **Ollama (self-hosted)** | | ✅ | | Model is preloaded and kept warm (`keep_alive`) |
| **DeepL** | | ✅ latency- or quality-optimized | | Free and Pro keys detected automatically. Sends context and formality |
| **Deepgram** | ✅ Nova-3 realtime + batch | | ✅ Aura-2, voice per language | **MIP opt-out toggle** |
| **AssemblyAI** | ✅ Universal-Streaming v3 + batch | | | EU region |
| **Cartesia** | ✅ Ink-Whisper | | ✅ Sonic 3, streams PCM | |
| **Groq** | ✅ Whisper large-v3-turbo (very fast) | ✅ | ✅ Orpheus | |
| **Cerebras** | | ✅ (very high token rate) | | |
| **Mistral** | ✅ Voxtral | ✅ | | |
| **Cohere** | | ✅ Command A Translate | | |
| **OpenRouter** | | ✅ hundreds of models | | **ZDR toggle** (`provider.zdr`, `data_collection: deny`) |
| **Together AI** | ✅ Whisper | ✅ | ✅ | |
| DeepSeek, xAI, Fireworks, SambaNova, Moonshot (Kimi), Qwen (DashScope), Z.ai (GLM), Hugging Face | | ✅ | | OpenAI-compatible presets |
| **Custom OpenAI-compatible** | ✅ | ✅ | ✅ | LM Studio, vLLM, LiteLLM, LocalAI, Speaches, Kokoro-FastAPI… |

## How the latency is kept down

1. **Realtime STT when available.** Audio streams over a WebSocket while you speak, so the final
   transcript arrives within a few hundred milliseconds of you stopping. With batch STT, a local voice
   activity detector (VAD) cuts the utterance the moment you pause, and it uploads as raw 16 kHz PCM.
   The default **noise-robust detector** looks at the speech band of an FFT spectrum against a tracked
   noise profile, so steady background noise (traffic, fans, cafés) doesn't keep it stuck "listening".
   *Settings → Listening → Speech detector* switches back to the basic volume-based one.
2. **Translating before you finish.** Once your words stop changing on screen, translation starts
   while the recogniser is still confirming the end of the sentence. If the final transcript matches
   (ignoring case and punctuation), the translation is already under way. Messages that benefited show
   *⚡ early MT*. There are at most 3 speculative requests per utterance; turn this off under
   *Settings → Translation*.
3. **Pre-warming.** When you tap a mic, the app opens TLS/HTTP-2 connections to the translation and
   TTS providers (and loads the model on self-hosted Ollama), so those requests start hot.
4. **Streaming translation.** Tokens are shown as they arrive and cut into speakable chunks. With
   *Start speaking early* on, the first chunk can end at a clause boundary.
5. **Speaking starts before the translation is finished.** With ElevenLabs (Flash, Turbo or
   Multilingual v2), the translation is streamed into the voice **word by word** over its `stream-input`
   WebSocket, which is opened as soon as the turn starts. With other providers, TTS for chunk 1 runs while
   the LLM is still writing chunk 2. Either way, audio is streamed as PCM straight into a low-latency
   `AudioTrack`, with no file or decoder in between. If the WebSocket fails, the turn falls back to
   sentences.
6. **Parallel turns, ordered playback.** Several turns can be in flight at once, but they're always
   spoken in conversation order. Tapping a mic interrupts playback (barge-in).
7. **Thinking is turned off** for models where it can be: Gemini thinking budget 0, Ollama
   `think: false`. Parameters a model rejects (for example `temperature` on reasoning models) are
   remembered and dropped automatically after the first error.

For the lowest latency, a good starting combination is **Deepgram Nova-3 or ElevenLabs Scribe
Realtime → Groq, Cerebras or Claude Haiku → ElevenLabs Flash v2.5 or Cartesia Sonic**. If you need
privacy, use the on-device stack, or turn on Command Code ZDR or OpenRouter ZDR.

## Building

Requirements: JDK 17 or newer (21 recommended) and an Android SDK with `platforms;android-37.0` and
`build-tools;36.0.0`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
# APKs: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk (and other ABIs + universal)
```

Stack: Kotlin 2.4, AGP 9.5 (built-in Kotlin), Gradle 9.8, Jetpack Compose with Material 3, OkHttp 5,
kotlinx.serialization and coroutines, ML Kit (on-device translation and language ID). minSdk 26,
targetSdk 36. ML Kit's offline translator adds about 16 MB of native code per ABI, so the build
splits APKs by ABI. Install the `arm64-v8a` APK on modern phones.

**Signed release builds.** Set `KALIBABEL_KEYSTORE_FILE`, `KALIBABEL_KEYSTORE_PASSWORD`,
`KALIBABEL_KEY_ALIAS` and `KALIBABEL_KEY_PASSWORD`, or create a git-ignored `keystore.properties`
containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without these, release builds
are signed with the debug key.

## CI: Gitea Actions (act_runner) and GitHub Actions

There are two workflows that do the same job using the same scripts in `scripts/ci/`:

- `.gitea/workflows/android.yml` runs on the Gitea mirror for **release tags** (and manual runs), and
  publishes Gitea releases.
- `.github/workflows/android.yml` runs on GitHub for every push and pull request, and publishes GitHub
  releases.

Each forge only reads its own folder. `versionCode` is the number of commits on `HEAD`, so a build of
a given commit gets the same code on both forges, and newer builds can always update older installs.
Each run:

1. installs JDK 21 and the Android SDK (`scripts/ci/setup-android-sdk.sh`; Gradle and the SDK are
   cached with `actions/cache`)
2. runs the unit tests and the Robolectric UI render tests (screenshots are uploaded with the reports).
   Robolectric's Android runtime jar is resolved through Gradle and cached with it, so tests run offline.
   CI downloads Maven Central artifacts through Google's mirror (`KALIBABEL_MAVEN_MIRROR`), because
   Maven Central rate-limits busy or self-hosted runner IPs (HTTP 429)
3. runs lint
4. builds debug and release APKs and uploads them as the `kalibabel-apks` artifact

### Publishing a release

`https://tig.sshah.email/Cheeba/kalibabel` is a pull mirror of GitHub, so releases start from a
**tag on GitHub**. There are two ways to create one:

- **From the browser:** go to GitHub → *Actions → Android CI → Run workflow*, keep the branch as `main`,
  and enter a release tag such as `v0.2.0`. The run builds, tests and signs first. Only if all of that
  passes does it push the tag (as `github-actions[bot]`) and publish the GitHub release. If anything
  fails, no tag is created.
- **From the command line:**

  ```bash
  git tag -a v0.2.0 -m "KaliBabel 0.2.0"
  git push origin v0.2.0
  ```

The steps below describe what happens on the Gitea mirror once the tag exists.

1. The Gitea mirror picks up the tag on its **next scheduled sync**. Nothing needs doing by hand: the
   release appears on Gitea on its own, just like KaliScriber's. How soon depends on the mirror
   interval (*Settings → Repository → Mirror settings → Interval*; Gitea's default is 8 hours, and
   something like `10m` makes releases show up within minutes). *Synchronize now* is only a shortcut.
2. The sync fires a `push` event for the tag. The workflow builds and signs the APKs, then
   `scripts/ci/gitea-release.sh` turns the synced tag into a full release on the mirror repo. It
   attaches the per-ABI APKs and `SHA256SUMS.txt`, and adds release notes listing every commit since
   the previous tag.
3. The job log and summary link straight to the release page.

Tags containing `-` (for example `v1.0.0-beta1`) are published as prereleases. Re-running the job
replaces the existing assets instead of duplicating them.

Releases only come from tags. Tags created on Gitea alone would be deleted again by the next mirror
sync, so builds from `main` are kept as workflow artifacts instead.

**Gitea-side requirements for releases:**

- **Gitea 1.21.5 or newer.** Older versions wipe all releases of pull mirrors on every sync.
- **The Actions and Releases units are enabled** on the mirror repo (repo *Settings → Repository*).
- **The Actions token can write releases.** The workflow requests `contents: write`, which is
  enough with the default permissive token mode (and in restricted mode on Gitea 1.26+).
  Otherwise, add a `RELEASE_TOKEN` secret with a personal token that has `write:repository`.
- **Uploads are allowed:** about 75 MB for the universal APK. Check `[repository.release] FILE_MAX_SIZE`
  and the reverse proxy body limit (for example nginx `client_max_body_size 200m`). If
  `[repository.release] ALLOWED_TYPES` is set, it must allow `.apk` and `.txt`.
- **Optional:** shorten the mirror interval (for example `10m`) so new commits and tags build
  sooner.

**Runner requirements:**

- An **x86_64** act_runner. Google only ships Linux build-tools (aapt2) for x86_64.
- The `ubuntu-latest` label must map to an image that includes Node. The default
  `docker://gitea/runner-images:ubuntu-latest` or `catthehacker/ubuntu:act-latest` both work.
- About 6 GB of RAM.
- Actions on the instance can resolve `actions/*` from GitHub (Gitea's default `DEFAULT_ACTIONS_URL`).

### Secrets and variables for APK releases

Set the same values in both places:

- **Gitea:** **Settings → Actions → Secrets / Variables**
  (https://tig.sshah.email/Cheeba/kalibabel/settings/actions/secrets)
- **GitHub:** **Settings → Secrets and variables → Actions**
  (https://github.com/sshahs/kalibabel/settings/secrets/actions)

`RELEASE_TOKEN` only applies to Gitea. GitHub uses its built-in token.

| Secret | Required | Value |
|---|---|---|
| `KEYSTORE_BASE64` | for `v*` tags | The release keystore, base64-encoded on one line |
| `KEYSTORE_PASSWORD` | for `v*` tags | The keystore password |
| `KEY_ALIAS` | for `v*` tags | The key alias (`kalibabel` if you use the script below) |
| `KEY_PASSWORD` | for `v*` tags | The key password. For PKCS12 keystores this is the same as `KEYSTORE_PASSWORD` |
| `RELEASE_TOKEN` | no | A personal access token with `write:repository`. Only needed if the automatic Actions token can't create releases on your instance |

| Variable | Default | Effect |
|---|---|---|
| `RELEASE_DRAFT` | unset | Set to `true` to publish releases as drafts, so you can review them before they go public |
| `ALLOW_DEBUG_SIGNED_RELEASE` | unset | Set to `true` to let tag builds run without signing secrets (testing only) |

**Creating the signing key and secrets.** Run this on your own machine (it needs a JDK), so the
private key never passes through CI logs or chat:

```bash
# Creates release-signing/kalibabel-release.jks (git-ignored) and uploads the four secrets.
# The token needs the write:repository scope: https://tig.sshah.email/user/settings/applications
GITEA_TOKEN=<your token> scripts/release-keystore.sh --upload

# Or, without a token: writes release-signing/gitea-secrets.txt for you to paste into the UI.
scripts/release-keystore.sh

# Already have a keystore? Reuse it:
scripts/release-keystore.sh --keystore path/to/existing.jks --upload
```

The script checks the alias and password before it uploads anything, then prints the certificate's
SHA-256 fingerprint. CI prints the same fingerprint in each release's notes, so users can verify
downloads.

**Back up the `.jks` file and its password** somewhere safe, such as a password manager plus an
offline copy. Android only installs an update over an existing install if it's signed with the same
key. If the key is lost, every user would have to uninstall and reinstall.

**Safeguards in CI** (`scripts/ci/decode-keystore.sh`, `scripts/ci/check-signing-cert.sh`):

- A `v*` tag build **fails** if the signing secrets are missing. A release signed with the debug key
  can never be upgraded to by a properly signed one.
- A wrong password or alias fails early with a clear error.
- Every APK is checked against the certificate fingerprint pinned in `.github/signing-cert-sha256`
  before anything is published. If either forge's `KEYSTORE_BASE64` ever held a different key, the
  release fails instead of shipping APKs that won't install as updates. If you ever change the
  signing key on purpose, update this file too.
- Builds of `main` without secrets still run, signed with the debug key.

Artifacts are uploaded with `actions/upload-artifact@v3`, because Gitea identifies itself as GHES,
which `@v4` rejects.

## Project layout

```
app/src/main/java/email/sshah/kalibabel/
├── audio/        AudioRecorder (16 kHz frames), Vad, AudioPlayer + ordered SpeechQueue, WAV helpers
├── data/         AppSettings, SettingsRepository, SecureKeyStore (Keystore AES-GCM), Languages, HistoryStore
├── net/          OkHttp helpers: SSE/NDJSON/byte streaming, WebSockets, error extraction
├── pipeline/     Interpreter (orchestration + metrics), SentenceChunker, LangTagParser, Prompts
├── providers/    Engine interfaces, ProviderSpecs (catalogue), ProviderRegistry
│   └── impl/     One class per API family (OpenAI-compatible, Anthropic, Gemini, ElevenLabs, Deepgram, …)
└── ui/           Compose screens: conversation, settings, providers, pickers
```

To add a provider, add a `ProviderSpec` (and an enum entry). If it speaks the OpenAI dialect, you're
done. Otherwise implement a `Provider` subclass that exposes `translation`, `stt` and/or `tts`, then
map it in `ProviderRegistry.create`.

## Status and caveats

- The build, unit tests, UI render tests, lint and signed release packaging are all verified in CI.
  Each provider integration was written against that provider's current API documentation, but
  **hasn't yet been exercised with live API keys**. Expect a few to need tweaks once tried: request
  parameter names, newer model IDs, voice defaults. Use *Test connection* on each provider screen.
- The default model IDs are only starting points. Use the model picker to choose from what your key
  can actually access.
- The microphone stops when the app goes to the background. A foreground service is on the roadmap
  below.

## Roadmap and ideas

**Latency**
- [x] **Speculative translation** of stable partial transcripts.
- [x] **Text-streaming TTS over WebSockets** for ElevenLabs. Cartesia and Deepgram WebSockets would be
  next.
- [ ] **Speech-to-speech realtime models** (OpenAI Realtime, Gemini Live) as a single-hop mode.
- [x] **A noise-robust speech detector** (spectral, pure Kotlin).
- [ ] **A neural VAD** (Silero via ONNX Runtime) for babble noise, such as other people talking. It
  would add about 6 MB per ABI.
- [ ] **Adaptive provider racing.** Send the request to two translation providers and use whichever
  answers first, or auto-pick based on measured p50 latency per network.

**Accuracy**
- [ ] **Automatic language-pair suggestion** from detected speech, and per-contact or per-trip presets
  (for example "Taxi in Tokyo").
- [ ] **Glossary import/export, and DeepL glossary sync.**
- [ ] **Show alternatives or back-translation**, so you can check the meaning came through.
- [ ] **Speaker-aware voices.** Clone or select a voice so each person's translation sounds like them
  (ElevenLabs voice cloning).

**Use cases**
- [ ] **Foreground service** with a notification control, so interpreter mode keeps working with the
  screen off.
- [ ] **Earbud mode.** You hear translations in Bluetooth earbuds while the other person hears your
  translation from the phone speaker. This needs audio routing per output.
- [ ] **Listen-only mode** for lectures, announcements and TV, with a live subtitle view and transcript
  export.
- [ ] **Camera/OCR translation** of signs and menus (ML Kit text recognition, then the same pipeline).
- [ ] **Phrasebook and offline packs** for common travel phrases.
- [ ] **A Wear OS tile or Quick Settings tile** to start the interpreter with one tap.
- [ ] **Usage and cost tracking** per provider, using the token and character counts the APIs return.

**Privacy**
- [ ] **A "Private mode" preset** that turns on every ZDR and opt-out toggle and refuses providers
  without one.
- [ ] **Encrypted history**, with automatic deletion after N days.
- [ ] **An optional "lite" build flavour without ML Kit**, about 5 MB instead of about 20 MB.
