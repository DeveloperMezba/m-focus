# M-Focus Android CI/CD

## Workflows

- `.github/workflows/build.yml`: Debug APK on pushes to `main`/`master`, pull requests, and manual runs. It uploads a 7-day Actions artifact.
- `.github/workflows/release-android.yml`: when a Stable GitHub Release is published, checks out that exact tag, builds a Release APK signed with the persistent keystore, validates the APK, and uploads `m-focus.apk` and its SHA-256 file to that same Release.

## Required repository Actions secrets

Set all four under **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64`: Base64 text of the existing JKS/keystore file.
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Use the exact same signing keystore and alias that signed the APK already distributed to users. Never generate a replacement key for an update. Keep an offline backup. The workflow never reads or prints the secret values.

The Debug workflow can run without signing secrets, but those fallback Debug APKs use Android's standard Debug key and cannot be updated in place by the Release key. When all four secrets are configured, CI Debug and Release APKs use the same key.

## Release procedure

1. Push the finished code to `main`.
2. Open **Releases → Draft a new release**.
3. Select the commit to release and create a stable tag, for example `v2.9.0`.
4. Publish the Release. GitHub Actions starts the Android Stable Release workflow automatically.
5. Wait until the workflow succeeds. It uploads `m-focus.apk` and `m-focus.apk.sha256` to the same Release.
6. Open **Settings → App update** in M-Focus to check for the update.

Immediately after publication the APK may not be attached yet. The updater reports that the version is being prepared; check again after the workflow completes.

## Versioning

The Release tag becomes Android `versionName`. Accepted stable forms include `v2.9`, `v2.9.0`, `v2.9.1`, and `v2.10.0`. A deterministic `versionCode` is calculated as:

`major * 1,000,000 + minor * 1,000 + patch`

Examples: `v2.9.0 → 2009000`, `v2.9.1 → 2009001`, `v2.10.0 → 2010000`. The workflow rejects malformed tags, unsupported components, and a release whose calculated versionCode is not greater than earlier stable Releases. Do not reuse or move an existing release tag.

## Update safety

The app ID remains `com.focusguard.phone`. The updater selects only the exact `m-focus.apk` asset, checks its repository/tag URL, validates the GitHub SHA-256 digest when available, and verifies the APK package ID, versionName, increasing versionCode, and signing certificate (including the legacy API 27 path). Verification errors fail closed.

The repository currently has `gradle/wrapper/gradle-wrapper.properties`, but is missing `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar`. Until the wrapper is restored, both workflows install Gradle 8.7 directly rather than trying to run the missing wrapper scripts.

The release workflow requires the target Release to remain mutable because it uploads the APK after the Release is published. The current repository's published Releases are not marked immutable. If immutable Releases are enabled later, use a draft-first/tag-triggered workflow instead.
