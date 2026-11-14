# Play Store Release Build Guide

This document describes the release build process for Jandal AI's first Play Store publication.


## Overview

Jandal AI uses **Google Play App Signing**. The flow is:

1. For the first signed release, Nick creates an upload keystore locally; no existing keystore is required.
2. Configure Gradle release signing using local-only `keystore.properties` (see [Signing Setup](#signing-setup)).
3. Build a **signed** release AAB with the upload key.
4. Upload the signed AAB to the Play Console.
5. Google Play App Signing generates and manages the app signing key used to sign APKs delivered to users.
6. Future releases are also signed with the same upload key before upload.

The upload key authenticates your uploads to Play Console; Google's app signing key signs the APKs that reach users.
Because the Play Store manages the final signing key, the upload key is only used to authenticate uploads — not to sign the APKs that reach users.

## Build Commands

```bash
./gradlew clean :app:bundleRelease -PversionCode=N
```

- `versionCode` is a monotonically increasing integer. The AAB is written to `app/build/outputs/bundle/release/app-release.aab`.
- The `versionName` in `build.gradle.kts` is `"0.1.0"` for the initial release.
- Release builds use `-PversionCode=N` when an explicit code is required; otherwise they default to `1`.
- Release signing reads `keystore.properties` at the repository root. A detached worktree cannot see an unrelated main-checkout file. To use the owner's existing file without copying it, pass `-PreleaseSigningPropertiesFile=/absolute/path/to/keystore.properties`; this path names the properties file only and does not expose its contents.

### Build output

| Property | Value |
|---|---|
| `applicationId` | `ai.jandal.assistant` |
| `versionCode` | `1` (first release) |
| `versionName` | `0.1.0` |
| `compileSdk` | `36` |
| `targetSdk` | `36` |
| `minSdk` | `35` |
| AAB path | `app/build/outputs/bundle/release/app-release.aab` |
| AAB size | ~227 MB (includes native libs for 4 ABIs) |

## Signing Setup

### Local builds

For this first signed release, Nick creates a new upload keystore on a trusted local host. Do not create or store release keys in the repository or ordinary CI; the only CI copy is the `play-release` environment secrets described in [Publish from GitHub](#publish-from-github). Keep the keystore outside the checkout and on an encrypted, owner-controlled volume.

Generate the key interactively with the JDK `keytool`; it prompts for passwords, which must not be passed as command-line arguments:

```bash
umask 077
mkdir -p "$HOME/.local/share/jandal"
chmod 700 "$HOME/.local/share/jandal"
keytool -genkeypair -v -storetype JKS \
  -keystore "$HOME/.local/share/jandal/jandal-upload.jks" \
  -alias jandal-upload -keyalg RSA -keysize 2048 -validity 10000
chmod 600 "$HOME/.local/share/jandal/jandal-upload.jks"
```

Use strong passwords and keep them in a password manager, separately from the keystore. Make encrypted backups, including one offline. Test a restored backup by running `keytool -list -v -keystore <path> -alias jandal-upload` (interactive password prompt) and comparing the certificate SHA-256 fingerprint.

Create the project-root `keystore.properties` locally. The file is ignored by `.gitignore`, but contains plaintext at rest; protect it with owner-only permissions:

```properties
storeFile=/absolute/path/outside/checkout/jandal-upload.jks
storePassword=<set-locally>
keyAlias=jandal-upload
keyPassword=<set-locally>
```

Replace placeholders locally; never paste password values into chat, GitHub, or repository files. Then run:

```bash
chmod 600 keystore.properties
```
Gradle uses this file only when present. All four entries and the keystore file are required; incomplete configuration fails without printing credential values. With no file, release builds remain unsigned for CI. The release variant keeps application ID `ai.jandal.assistant`. For a local signing-properties file outside the checkout, pass its path with `-PreleaseSigningPropertiesFile=/absolute/path/to/keystore.properties`; Gradle reads the file directly, and the file remains untracked.

## Migration impact

Existing `com.kernel.ai` installs will not upgrade in place, and app data will not carry over to `ai.jandal.assistant`. The owner may uninstall the old app whenever they choose.
Build from a clean checkout at the approved full source SHA. Nick must approve the monotonically increasing `versionCode`; choose one fixed ISO-8601 UTC `buildTimestamp` and reuse both values exactly for any rebuild. Gradle embeds the timestamp in `BuildConfig`, so fixing it avoids build-time variation in that field.

```bash
git rev-parse HEAD
test -z "$(git status --porcelain)"
read -r -p "Nick-approved versionCode: " RC_VERSION_CODE
read -r -p "Fixed buildTimestamp (ISO-8601 UTC): " RC_BUILD_TIMESTAMP
./gradlew clean :app:assembleRelease \
  -PversionCode="$RC_VERSION_CODE" \
  -PbuildTimestamp="$RC_BUILD_TIMESTAMP"
```

With signing configured, the APK is `app/build/outputs/apk/release/app-release.apk`. Record the full source SHA, build timestamp, `versionName`, `versionCode`, signer certificate SHA-256, and APK SHA-256 with the test record. The embedded `BuildConfig.GIT_SHA` is the first eight characters of the source commit.

For bit-for-bit reproducibility, repeat the clean build with the same full source SHA, toolchain, approved version code, fixed timestamp, and signing key; compare the resulting APK SHA-256 values. If they differ, record that reproducibility was not demonstrated.


Verify package/version, signature, signer, and artifact digest before treating the APK as an RC candidate:

```bash
aapt dump badging app/build/outputs/apk/release/app-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Confirm `package: name='ai.jandal.assistant'` and the approved version values in the `aapt` output. Record the signer certificate digest from `apksigner` and the file digest from `sha256sum`. This creates a locally signed APK only; it does not upload to Play or prove Play-delivered signing/acceptance.

The same local-only signing configuration can sign a release AAB:

```bash
read -r -p "Nick-approved versionCode: " RC_VERSION_CODE
read -r -p "Fixed buildTimestamp (ISO-8601 UTC): " RC_BUILD_TIMESTAMP
./gradlew clean :app:bundleRelease \
  -PversionCode="$RC_VERSION_CODE" \
  -PbuildTimestamp="$RC_BUILD_TIMESTAMP"
```

The AAB is written to `app/build/outputs/bundle/release/app-release.aab`.

### CI builds

Ordinary CI (`ci.yml`) still does not receive or use release signing or Play credentials; only the manual, approval-gated [Play release workflow](#publish-from-github) does. CI's release APK/AAB outputs remain unsigned and are used only for build/packaging checks. CI's explicit `-PversionCode=${{ github.run_number }}` is preserved for unsigned builds; GPP's `AUTO` resolution runs only for a publish task and selects the next Play versionCode when uploading.

## One-command internal Play upload

GPP publishing runs either locally by the owner or from the manual, approval-gated [Play release workflow](#publish-from-github). Credential checks are gated to publish-bundle invocations, so missing Play credentials do not affect builds, tests, lint, or CI. The first app registration/upload must be completed manually in Play Console; subsequent signed releases can use GPP. The app applies the official `com.github.triplet.play` plugin, Gradle Play Publisher 4.1.1, the current stable release compatible with AGP 9.

### One-time owner setup

1. In Google Cloud Console, create a project (or select the owner-controlled project used for Jandal) and enable the **Google Play Android Developer API**.
2. Create a service account in that project, then create and download a JSON key. Store it outside the repository at `$HOME/.local/share/jandal/play-publisher.json` (or supply `-PplayServiceAccountFile=/absolute/path/to/key.json` / `PLAY_SERVICE_ACCOUNT_FILE`).
3. Protect it: `mkdir -p "$HOME/.local/share/jandal" && chmod 700 "$HOME/.local/share/jandal" && chmod 600 "$HOME/.local/share/jandal/play-publisher.json"`. Never commit, print, or copy this key into the checkout.
4. In Play Console → **Users and permissions**, invite the service-account email and grant access only to **Jandal AI**. Enable testing-track release permissions only; do not grant production release or listing-management permissions.
5. Keep the upload keystore and its local signing properties configured as described in [Signing Setup](#signing-setup). A detached worktree can refer to the owner's properties file by path without copying it.
6. No separate bundletool install is needed: `:app:verifyReleaseBundleForPlayUpload` resolves bundletool through Gradle and passes it to the verifier. A `bundletool` on `PATH` or a `BUNDLETOOL_JAR` still takes precedence when present.

### Publish to internal testing

```bash
ANDROID_HOME=~/.local/share/android-sdk \
  ./gradlew :app:publishReleaseBundle \
  -PreleaseSigningPropertiesFile="$HOME/.local/share/jandal/keystore.properties"
```

To publish to the closed test instead, add `-PplayTrack=alpha`. In this repo's main checkout the signing properties file is `keystore.properties` at the checkout root; pass that path when publishing from a worktree.

Update `app/src/main/play/release-notes/en-AU/default.txt` before each upload (Play allows 500 characters).

This uploads the signed release AAB to the **internal** track. `-PplayTrack` accepts only `internal`, `alpha` (closed testing), or `beta`; production and promotion tasks are disabled. GPP's `AUTO` version resolution chooses one more than the highest Play version for an upload, while explicit `-PversionCode` remains effective for non-publish builds such as CI. Release notes come from `app/src/main/play/release-notes/en-AU/default.txt`. Store listing text and images remain console-managed and are not part of bundle publishing.

The local command above remains the alternative to publishing from GitHub.

### Publish from GitHub

`.github/workflows/play-release.yml` runs the same `:app:publishReleaseBundle` task on a GitHub runner. It is `workflow_dispatch` only, refuses any ref other than `main`, and reads its credentials from secrets scoped to the `play-release` environment, so every run waits for the owner's approval before any secret is exposed. It offers only the `internal` and `alpha` (closed testing) tracks; there is no production option.

#### One-time setup

The `play-release` environment (repository **Settings › Environments › play-release**) is configured as:

| Setting | Value |
|---|---|
| Required reviewers | `NickMonrad` |
| Prevent self-review | Off. The owner both starts and approves runs; turning it on would leave nobody able to approve. |
| Allow administrators to bypass configured protection rules | Off, so admin rights cannot skip the approval. |
| Deployment branches and tags | **Selected branches and tags**, `main` only |

It holds these five **environment** secrets (not repository secrets):

| Secret | Value | Set with |
|---|---|---|
| `PLAY_UPLOAD_KEYSTORE_BASE64` | `base64 -w0` of the upload keystore | `gh`, piped (below) |
| `PLAY_SERVICE_ACCOUNT_JSON` | Full contents of the service-account JSON key | `gh`, stdin redirect (below) |
| `PLAY_UPLOAD_STORE_PASSWORD` | Keystore password | Web UI |
| `PLAY_UPLOAD_KEY_ALIAS` | Key alias, e.g. `jandal-upload` | Web UI |
| `PLAY_UPLOAD_KEY_PASSWORD` | Key password | Web UI |

Set the two file-based secrets on the owner host by streaming the files straight into `gh`, so the values never appear as a command-line argument, in shell history, on screen, or in a file in the checkout:

```bash
base64 -w0 ~/.local/share/jandal/jandal-upload.jks | gh secret set PLAY_UPLOAD_KEYSTORE_BASE64 --env play-release
gh secret set PLAY_SERVICE_ACCOUNT_JSON --env play-release < ~/.local/share/jandal/play-publisher.json
```

Type the three short secrets in **Settings › Environments › play-release › Environment secrets › Add environment secret**. To rotate a value, repeat the same command or form; nothing else changes. The service account holds testing-track release permissions only (see [One-time owner setup](#one-time-owner-setup)), and the app's first upload must already have been made manually in Play Console, because `AUTO` version resolution reads the existing Play version codes.

#### Release steps

1. Update `app/src/main/play/release-notes/en-AU/default.txt` and merge it to `main`. The run builds the `main` commit current when it is started, so the notes must be merged first.
2. **Actions › Play release › Run workflow**: leave **Use workflow from** on `main`, choose the track (`internal` or `alpha`), then **Run workflow**. Any other branch fails in the `Require main` job without requesting approval.
3. Open the run. `Publish to Play` waits for review; choose **Review deployments**, tick `play-release`, then **Approve and deploy**. The job receives the secrets only after this approval.
4. The job builds and signs the AAB, rejects it if unsigned (`verifyReleaseBundleForPlayUpload`), uploads it to the chosen track with the next Play `versionCode`, and removes the credentials. Check the release under the track in Play Console.
5. For an `alpha` run, or an `internal` run with **github_release** ticked, the same run also builds a signed universal APK from the uploaded `versionCode` and publishes it as a full GitHub release (see below).

The workflow decodes the keystore and writes the signing properties file and service-account JSON under `$RUNNER_TEMP` (outside the checkout) with `umask 077`, passes only their paths to Gradle, and deletes them in an always-run step. Passwords never appear as `-P` arguments.

#### GitHub APK for testers without Play

Testers who don't use the Play Store get the same build as the closed test track as a single signed APK on [GitHub Releases](https://github.com/NickMonrad/kernel-ai-assistant/releases). The `publish` job builds it after the Play upload, using the `versionCode` read back from the uploaded bundle, and stages it as a short-lived artifact. The `github-release` job has `contents: write` and no secrets; it only downloads that artifact and creates the release. The signing job keeps `contents: read`.

- **Tag and title:** `v0.1.<versionCode>` and `Jandal AI 0.1.<versionCode>`, for example `v0.1.5`. Full releases, not pre-releases.
- **versionName:** `0.1.<versionCode>`, passed as `-PversionName` for the tester APK only. It equals the tag without the `v`, so Obtainium can compare them. The Play bundle keeps `versionName` `0.1.0`; Play delivers by `versionCode`.
- **Asset:** exactly one, `jandal-ai-0.1.<versionCode>.apk`, signed with the upload certificate. The SHA-256 is in the release body, not a second asset.
- **Checks before release:** `aapt2 dump badging` must show `ai.jandal.assistant`, the `versionCode`, and the `versionName`; the `apksigner` signer digest must equal the uploaded bundle's upload certificate.
- **Not reproducible here:** this path has not been run. Confirm after the first alpha run that the GitHub `versionCode` matches the Play track's new build.
- **Re-running** for an existing `versionCode` fails at `gh release create` because the tag exists. Each Play upload gets a new `versionCode`, so this only happens on a manual re-run.

##### Install notes for testers

- Download `jandal-ai-0.1.<versionCode>.apk`, allow installs from your browser, and open it.
- Updates: install the newer APK over the top; settings and downloads are kept.
- **Can't mix with Play installs.** Play re-signs with Google's app-signing key, so the GitHub APK has a different signature. Switching between Play and GitHub needs an uninstall first, which deletes the app's data and downloaded models.
- No automatic updates in the app. Watch the repo's releases, or use Obtainium below.
- Feedback: Settings → About → Email support, or a GitHub issue.

#### Obtainium

Obtainium (ImranR98/Obtainium) tracks the GitHub releases directly:

1. Add app, source **GitHub**, URL `https://github.com/NickMonrad/kernel-ai-assistant`.
2. Leave **Include prereleases** off. Tester builds are full releases, so Obtainium's default "latest release" finds them. Debug and PR builds are pre-releases and are ignored.
3. Optional: set the APK filter regex to `^jandal-ai-.*\.apk$` so only the tester APK is considered.
4. Obtainium compares the release tag (`v0.1.<versionCode>`, with the `v` stripped) with the installed app's versionName. Both are `0.1.<n>` in standard `x.y.z` form, so version detection stays enabled and each build is distinct. Leave **Verify latest tag** at its default.

Obtainium cannot install over a Play-installed copy (different signature), so use one channel per device.

## Security Rules

- Keystore files and passwords are never committed; `keystore.properties` is ignored and contains plaintext at rest.
- Keep the locally generated upload keystore outside the checkout; retain encrypted backups, including one offline, and store passwords separately in a password manager.
- Never pass passwords in command-line arguments or Gradle `-P`/`-D` properties. Gradle reads them only from a signing properties file: the ignored local `keystore.properties` (owner read/write only), or the owner-only temporary file the Play release workflow writes and deletes.
- Play Console credentials and service-account keys remain outside the repository.
- Ordinary CI gets no release signing material and continues producing unsigned release outputs. Only the manual Play release workflow, gated by the `play-release` environment's required reviewer and `main` branch restriction, receives signing and Play credentials.

## Versioning Convention

`versionCode` is a monotonically increasing integer. Local non-publish builds default to `1` unless `-PversionCode` is supplied. GPP `ResolutionStrategy.AUTO` resolves a publishing bundle to the next available Play versionCode; it does not change CI's explicit run-number versionCode because CI does not execute publish tasks. `versionName` remains `"0.1.0"` for Play and local builds; only the tester GitHub APK uses `0.1.<versionCode>` (see [GitHub APK for testers](#github-apk-for-testers-without-play)).



## Play Console Flow

1. **Create your app** in the Play Console (if not already created).
2. **Upload the signed AAB** to the Internal Testing or Production track.
3. **Play App Signing enrollment** — Google will prompt you to enroll. Follow the on-screen flow to register your upload key.
4. Once enrolled, Google manages the production signing key. Future signed AAB uploads are automatically re-signed with the enrolled app signing key for user delivery.


### Local validation

Use `jarsigner` and `keytool` to verify the AAB signature, signer certificate, and digest. If `bundletool` is installed, validate the bundle structure as well:

```bash
bundletool validate --bundle=app/build/outputs/bundle/release/app-release.aab
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
sha256sum app/build/outputs/bundle/release/app-release.aab
```

Compare the AAB signer certificate SHA-256 fingerprint with `keytool -list -v -keystore <path> -alias <alias>` output (interactive password prompt). Record that fingerprint and the AAB SHA-256 with the source SHA, `versionName`, Nick-approved `versionCode`, and fixed `buildTimestamp`. Verify the APK separately with `apksigner verify --verbose --print-certs` and `sha256sum`; compare its signer fingerprint with the local keystore listing. These commands do not sign or upload either artifact.

### AAB upload rejected by Play Console

Verify the AAB is signed (not unsigned) by running:

```bash
# For AABs: verify JAR signature
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab

# If bundletool is installed: validate the bundle structure
bundletool validate --bundle=app/build/outputs/bundle/release/app-release.aab
```

### Gradle version mismatch

The project requires Gradle 9.1.0 and AGP 9.0.1. If the Gradle wrapper is out of date, run `./gradlew wrapper --gradle-version=9.1.0`.
