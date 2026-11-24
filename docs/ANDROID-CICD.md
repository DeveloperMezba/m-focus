# M-Focus Android CI/CD

## Workflows

- `.github/workflows/build.yml`: builds a Debug APK on pushes to `main`/`master`, pull requests, and manual runs. It uploads the APK as a 7-day GitHub Actions artifact.
- There is no automatic Release workflow. You create and publish GitHub Releases manually and attach the APK yourself.

## Required repository Actions secrets

Set all four under **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64`: Base64 text of the existing JKS/keystore file.
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Use the exact same signing keystore and alias that signed the APK already distributed to users. Never generate a replacement key for an update. Keep an offline backup. The workflow never reads or prints the secret values.

The Debug workflow can run without signing secrets, but those fallback Debug APKs use Android's standard Debug key and cannot update an installation signed with the Release key. When all four secrets are configured, the CI Debug APK is signed with the persistent Release key and can be update-compatible, provided the app versionCode is increased.

## Manual Release procedure

1. Run the **Android Debug Build** workflow (or wait for the pull-request/push build).
2. Open the successful workflow run and download its `m-focus-debug-<run number>` artifact.
3. Extract `app-debug.apk` and verify that it is signed with the same persistent key as the installed app. Do not distribute a fallback Debug-key APK as an in-place update.
4. Ensure the app's `versionCode` is greater than the version already installed.
5. Open **Releases → Draft a new release**, choose the intended commit, create the release/tag, and manually attach the APK (you may name it `m-focus.apk` for the in-app updater).
6. Publish the Release. The app's updater can then check the manually attached asset.

No workflow automatically creates a Release or uploads an APK to one.

## Versioning

The APK version is controlled by the Android project configuration unless you explicitly set build version environment values. Before publishing an update, increase `versionCode` above the previously distributed APK and set the intended `versionName`. Do not reuse or move an existing release tag.

## Update safety

The app ID remains `com.focusguard.phone`. The updater selects only the exact `m-focus.apk` asset, checks its repository/tag URL, validates the GitHub SHA-256 digest when available, and verifies the APK package ID, versionName, increasing versionCode, and signing certificate (including the legacy API 27 path). Verification errors fail closed.

An APK update can install over the existing app only when its application ID matches, its versionCode is higher, and it is signed by the same signing certificate. The CI Debug workflow's standard Debug-key fallback does not meet the signing requirement for users who installed the Release-key APK.

The repository currently has `gradle/wrapper/gradle-wrapper.properties`, but is missing `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar`. Until the wrapper is restored, the workflow installs Gradle 8.7 directly rather than trying to run the missing wrapper scripts.
