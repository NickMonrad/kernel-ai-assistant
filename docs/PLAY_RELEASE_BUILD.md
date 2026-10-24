# Play Store Release Build Guide

This document describes the release build process for Jandal AI's first Play Store publication.


## Overview

Jandal AI uses **Google Play App Signing**. The flow is:

1. Nick provides the existing authorized upload keystore and upload key; do not generate a repository or agent-owned release key.
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

- `versionCode` is a monotonically increasing integer. Start at `1`.
- The AAB is written to `app/build/outputs/bundle/release/app-release.aab`.
- The `versionName` in `build.gradle.kts` is `"0.1.0"` for the initial release.

### Build output

| Property | Value |
|---|---|
| `applicationId` | `com.kernel.ai` |
| `versionCode` | `1` (first release) |
| `versionName` | `0.1.0` |
| `compileSdk` | `36` |
| `targetSdk` | `36` |
| `minSdk` | `35` |
| AAB path | `app/build/outputs/bundle/release/app-release.aab` |
| AAB size | ~227 MB (includes native libs for 4 ABIs) |

## Signing Setup

### Local builds

For local signed release-candidate APKs, Nick must provide the existing authorized upload keystore and its credentials. This repository does not generate or contain a release private key. Keep the keystore outside the checkout.

Create a project-root `keystore.properties` file locally (already ignored by `.gitignore`) and restrict its permissions:

```properties
storeFile=/absolute/path/to/the/Nick-provided-upload.keystore
storePassword=<Nick-provided-store-password>
keyAlias=<Nick-provided-alias>
keyPassword=<Nick-provided-key-password>
```

```bash
chmod 600 keystore.properties
```

Gradle uses this file only when present. All four entries and the keystore file are required; incomplete configuration fails without printing credential values. With no file, release builds remain unsigned for CI. The release variant keeps application ID `com.kernel.ai`.

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

Confirm `package: name='com.kernel.ai'` and the approved version values in the `aapt` output. Record the signer certificate digest from `apksigner` and the file digest from `sha256sum`. This creates a locally signed APK only; it does not upload to Play or prove Play-delivered signing/acceptance.

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

CI intentionally does not receive or use release signing material. Its release APK/AAB outputs remain unsigned and are used only for build/packaging checks.


## Security Rules

- Keystore files and passwords are never committed; `keystore.properties` is ignored and contains plaintext at rest.
- Keep the supplied keystore outside the checkout and restrict the properties file to owner read/write.
- Never pass passwords through command-line arguments or CI/local Gradle properties.
- Play Console credentials and service-account keys remain outside the repository.
- CI gets no release signing material and continues producing unsigned release outputs.

## Versioning Convention

`versionCode` is a monotonically increasing integer; Nick must choose and approve the candidate value. `versionName` remains `"0.1.0"` for this release candidate.



## Play Console Flow

1. **Create your app** in the Play Console (if not already created).
2. **Upload the signed AAB** to the Internal Testing or Production track.
3. **Play App Signing enrollment** — Google will prompt you to enroll. Follow the on-screen flow to register your upload key.
4. Once enrolled, Google manages the production signing key. Future signed AAB uploads are automatically re-signed with the enrolled app signing key for user delivery.


### Local validation

If you have `bundletool` installed, you can validate an AAB locally:

```bash
bundletool validate --bundle=app/build/outputs/bundle/release/app-release.aab
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```


Confirm the keystore file exists at the path specified in `keystore.properties` and that the alias is correct. Run `keytool -list -v -keystore <path>` without `-storepass`; keytool prompts for the password instead of exposing it in the process arguments.

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
