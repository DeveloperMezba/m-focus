# M-Focus Android CI/CD

## Workflows

- `.github/workflows/build.yml`: builds a Debug APK on pushes to `main`/`master`, pull requests, and manual runs. It uploads the APK as a 7-day GitHub Actions artifact.
- For manual runs you can set `app_version_code` and `app_version_name` inputs; the built APK then carries that version instead of the project default (`9` / `2.9`).
- There is no automatic Release workflow. You create and publish GitHub Releases manually and attach the APK yourself.

## Required repository Actions secrets

Set all four under **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64`: Base64 text of the existing JKS/keystore file.
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Use the exact same signing keystore and alias that signed the APK already distributed to users. Never generate a replacement key for an update. Keep an offline backup. The workflow never reads or prints the secret values.

The Debug workflow can run without signing secrets, but those fallback Debug APKs use Android's standard Debug key and cannot update an installation signed with the Release key. When all four secrets are configured, the CI Debug APK is signed with the persistent Release key. If the secrets exist but the keystore cannot be opened with the given password/alias, the build now **fails** instead of silently falling back to the debug key.

## Manual Release procedure

1. Run the **Android Debug Build** workflow with `app_version_code` and `app_version_name` inputs taken from the release tag (for example code `9`, name `2.9`). The inputs must give a versionCode strictly higher than every previously published APK.
2. Open the successful workflow run and download its `m-focus-debug-<run number>` artifact.
3. Extract `app-debug.apk` and verify that it is signed with the same persistent key as the installed app. Do not distribute a fallback Debug-key APK as an in-place update.
4. Confirm the APK `versionCode` is greater than the version already installed by users.
5. Open **Releases → Draft a new release**, choose the intended commit, create the release/tag, and manually attach the APK named exactly `m-focus.apk` for the in-app updater.
6. Add a `versionCode: <code>` line to the release notes body. The in-app updater reads it and will not offer an update whose declared versionCode is not higher than the installed one, avoiding a full download that then gets rejected.
7. Publish the Release. The app's updater can then check the manually attached asset.

No workflow automatically creates a Release or uploads an APK to one.

## Versioning

The project default is currently `versionCode = 9`, `versionName = "2.9"`; release-tag-derived `APP_VERSION_CODE`/`APP_VERSION_NAME` environment values override it. **Every distributed update must carry a versionCode strictly higher than the previous one.** Bumping `versionName` without bumping `versionCode` makes the updater offer the download and then reject it at install time. Do not reuse or move an existing release tag.

## Unit tests

`app/src/test/java/com/focusguard/phone/UpdaterVersionTest.kt` covers version parsing and comparison. Run them locally with `gradle testDebugUnitTest` (or `test`) — CI has no separate test job yet because the Gradle wrapper is still missing.

## Update safety

The app ID remains `com.focusguard.phone`. The updater selects only the exact `m-focus.apk` asset, checks its repository/tag URL, validates the GitHub SHA-256 digest when available, and verifies the APK package ID, versionName, increasing versionCode, and signing certificate (including the legacy API 27 path). Verification errors fail closed. The updater also pre-checks a `versionCode:` declaration in the release notes before offering a download.

An APK update can install over the existing app only when its application ID matches, its versionCode is higher, and it is signed by the same signing certificate. The CI Debug workflow's standard Debug-key fallback does not meet the signing requirement for users who installed the Release-key APK.

The repository currently has `gradle/wrapper/gradle-wrapper.properties`, but is missing `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar`. Until the wrapper is restored, the workflow installs Gradle 8.7 directly rather than trying to run the missing wrapper scripts.
