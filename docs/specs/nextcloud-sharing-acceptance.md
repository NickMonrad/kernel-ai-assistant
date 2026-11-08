# Nextcloud sharing physical acceptance (#1548)

`scripts/nextcloud_sharing_acceptance.py` runs one selected instrumentation method at a time against
two already-configured Nextcloud accounts. It does not install APKs, clear app data, mutate
ungenerated lists, save instrument output, or print a server URL, username, password, token, or raw
exception. Local fixture lists are retired through the normal delete path (tombstones remain in
Room); only calendars whose href path segment matches the generated slug plus its UUID (case-insensitively, with an optional Nextcloud `_shared_by_` sharee suffix) are deleted on the server.

## Approval gate

Do not install or run a candidate until a reviewer approves the exact pushed head. The runner requires
that full SHA as `--approved-head`, checks the current clean checkout against it, and the device test
checks the installed app's embedded Git SHA before each step. It also verifies each device serial,
model, target package, and version code before every instrumentation invocation. The harness has no
install command; install the two APKs manually only after approval.

CI remains provider-credential-free. The instrumentation test is skipped unless
`nextcloud_acceptance=true` is passed, and this runner is the only caller that supplies it.

## Preconditions

- Two different connected devices: owner on Samsung S21 (`SM-G991B`) and recipient on Samsung S23
  Ultra (`SM-S918B`). Supply their ADB serials directly to the script; the script does not print
  them.
- The same reviewer-approved debug APK and its `app-debug-androidTest.apk` are installed on both
  devices. Existing app data and the encrypted Nextcloud account setup must remain intact. The target
  app process does not need to remain running: Android instrumentation owns the per-step process
  lifecycle and may force-stop it between steps.
- The owner app is configured with a Nextcloud account that can create a VTODO calendar. The
  recipient app is configured with a different Nextcloud account. Neither password nor app password
  is given to the harness.
- Build both APKs from the approved commit, without installing them during build. Set the same
  version code used for the later package-identity check:

  ```sh
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PversionCode=<candidate-version-code>
  ```

  After the exact head is approved, install `app/build/outputs/apk/debug/app-debug.apk` and
  `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` on both devices. Do not use
  `installDebug`, `connectedDebugAndroidTest`, app-data clearing, or a different candidate build for
  this acceptance run.

## Run

From the clean checkout at the approved head, use an interactive terminal. The recipient's account
username is requested through a masked prompt and is used only to verify the two account roles and
address the exact sharee. Credentials remain in each app's encrypted preferences.

```sh
python3 scripts/nextcloud_sharing_acceptance.py \
  --owner-serial <S21-ADB-serial> \
  --recipient-serial <S23U-ADB-serial> \
  --expected-version-code <candidate-version-code> \
  --approved-head <full-reviewed-commit-SHA>
```

The first generated `J1548-<random-hex>` collection checks editable bidirectional sync, owner changes
on a read-only share, stale edits after a downgrade with Keep and Discard, proactive read-only copy,
removed-share handling without local work, and non-association after re-sharing. The second generated
collection checks stale edits made after share removal, Keep, and non-association after re-sharing.
The recipient-side Android test verifies copies have fresh collection/item identities and no binding,
provider item metadata, or pending provider changes. The owner account verifies the stranded/local-only
items never reach its calendar.

The runner rechecks device serial/model, installed target/test packages, and the installed app version
before every action. A running target-app process is not required: Android instrumentation can
force-stop the target package at each step's start and completion. The runner invokes only
`NextcloudSharingAcceptanceTest.runRequestedAcceptanceStep`. It emits only device aliases, step
names, generated fixture names, and PASS/FAIL/CLEAN status; Android instrumentation output is
captured and suppressed. On failure, automated cleanup runs only for owner fixtures whose exact
generated-name creation returned PASS. If `owner-create` does not return PASS, the candidate name is
reported as `CREATE INCOMPLETE`; no deletion is attempted because preflight or creation may have
partially completed. Inspect only that generated fixture if needed. If later cleanup reports
incomplete, inspect only that generated fixture before manual cleanup. Never delete a list or
calendar based on a partial title match.
