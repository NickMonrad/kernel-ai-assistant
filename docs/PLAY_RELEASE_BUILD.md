# Play Store Release Build Guide

This document describes the release build process for Jandal AI's first Play Store publication.


## Overview

Jandal AI uses **Google Play App Signing**. The flow is:

1. Generate an upload keystore and upload key on your local machine.
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

For local development builds, create a `keystore.properties` file in the project root with placeholder values:

```properties
storePassword=<set-this>
keyPassword=<set-this>
keyAlias=upload
storeFile=../keystore/release.keystore
```

**Add `keystore.properties` to `.gitignore`.** It must never be committed.

Then add the signing configuration to `app/build.gradle.kts`:

```kotlin
val keystoreProperties = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let {
        val props = java.util.Properties().apply {
            load(it.inputStream())
        }
        mapOf(
            "storePassword" to props["storePassword"],
            "keyPassword" to props["keyPassword"],
            "keyAlias" to props["keyAlias"],
            "storeFile" to props["storeFile"]
        )
    }

android {
    buildTypes {
        release {
            if (keystoreProperties != null) {
                signingConfig = signingConfigs.create("upload") {
                    storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                    storePassword = keystoreProperties["storePassword"] as String
                    keyAlias = keystoreProperties["keyAlias"] as String
                    keyPassword = keystoreProperties["keyPassword"] as String
                }
            }
        }
    }
}
```

### CI builds

For CI pipelines, use environment variables instead of a properties file:

| Variable | Purpose |
|---|---|
| `KEYSTORE_PATH` | Absolute path to the keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias name for the key |
| `KEY_PASSWORD` | Key password |

Do not pass secrets through `local.properties` in CI — that file is not part of the repository and is not available in pipeline environments.

## Play Console Flow

1. **Create your app** in the Play Console (if not already created).
2. **Upload the signed AAB** to the Internal Testing or Production track.
3. **Play App Signing enrollment** — Google will prompt you to enroll. Follow the on-screen flow to register your upload key.
4. Once enrolled, Google manages the production signing key. Future signed AAB uploads are automatically re-signed with the enrolled app signing key for user delivery.


### Local validation

If you have `bundletool` installed, you can validate the AAB locally:

```bash
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
    --output=app.apks \
    --ks=keystore/release.keystore \
    --ks-pass=pass:<store-password> \
    --ks-key-alias=upload \
    --key-pass=pass:<key-password>
```

## Security Rules

- **Keystore files are not committed** to the repository.
- **Passwords are not committed** — use environment variables or a local-only properties file.
- **Play Console secrets are not committed** — API keys, service account keys, and any Play Console credentials remain outside the repository.
- **`local.properties` is not used for secrets in CI** — it is a per-developer file and unavailable in pipeline environments.
- **`keystore.properties` must be added to `.gitignore`** — this file contains credentials and must never be committed.

## Versioning Convention

- **`versionCode`**: A monotonically increasing integer. Each release must have a higher `versionCode` than the previous one. Start at `1`.
- **`versionName`**: A human-readable semantic version string. The initial release uses `"0.1.0"`.

Verify the AAB is signed (not unsigned) by running:

```bash
# For AABs: verify JAR signature
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab

# If bundletool is installed: validate the bundle structure
bundletool validate --bundle=app/build/outputs/bundle/release/app-release.aab
```

For APK validation (after extracting APKs from the AAB via bundletool):

```bash
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
    --output=app.apks \
    --ks=<keystore-path> \
    --ks-pass=pass:<store-password> \
    --ks-key-alias=upload \
    --key-pass=pass:<key-password>

# Then verify the generated APK
apksigner verify --verbose app/signed.apk
```

Ensure the keystore file exists at the path specified in `keystore.properties` and that the alias is correct. Run `keytool -list -v -keystore <path> -storepass <password>` to verify.

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
