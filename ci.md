# CI and Distribution

Adapted from the Glimpse `build.yml`. Same structure, three changes: tests gate
the build, Firebase is optional, and updates verify signatures.

---

## Firebase — deferred to Phase 5

Ledgerly is offline-first. Phases 0–4 need **no Firebase at all**. Do not add
`google-services.json`, the Firebase Gradle plugin, or any Firebase dependency
before Phase 5.

When Phase 5 arrives, the free Spark tier is sufficient: Auth (email/password)
and Firestore within free quota. A single user syncing encrypted monthly blobs
generates a handful of writes per day. No GCP services, no Cloud Functions, no
paid tier.

Until then the workflow needs no `GOOGLE_SERVICES_JSON` secret. Keep the
placeholder-fallback pattern from Glimpse when you add it — it keeps the build
green without credentials.

---

## Workflow

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      # Gates everything below. Crypto, money-type, reconciliation and
      # golden-test failures must never produce an installable APK.
      - name: Run unit tests
        run: ./gradlew test --stacktrace

      - name: Upload test report on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/build/reports/tests/'

      - name: Check release signing secrets
        id: release_secrets
        if: (github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main'
        env:
          RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          if [ -n "$RELEASE_KEYSTORE_BASE64" ]; then
            echo "present=true" >> "$GITHUB_OUTPUT"
          else
            echo "present=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Build debug APK
        if: steps.release_secrets.outputs.present != 'true'
        run: ./gradlew assembleDebug --stacktrace

      - name: Read app version
        id: app_version
        run: |
          VERSION_NAME=$(grep -m1 'val appVersionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
          VERSION_CODE=$(grep -m1 'val appVersionCode' app/build.gradle.kts | sed -E 's/.*= *([0-9]+).*/\1/')
          echo "version_name=$VERSION_NAME" >> "$GITHUB_OUTPUT"
          echo "version_code=$VERSION_CODE" >> "$GITHUB_OUTPUT"

      - name: Rename debug APK
        if: steps.release_secrets.outputs.present != 'true'
        run: |
          mv app/build/outputs/apk/debug/app-debug.apk \
             "app/build/outputs/apk/debug/Ledgerly-v${{ steps.app_version.outputs.version_name }}-build${{ github.run_number }}-debug.apk"

      - name: Decode release keystore
        if: steps.release_secrets.outputs.present == 'true'
        env:
          RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: echo "$RELEASE_KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.keystore.jks"

      - name: Build signed release APK
        if: steps.release_secrets.outputs.present == 'true'
        env:
          RELEASE_KEYSTORE_PATH: ${{ runner.temp }}/release.keystore.jks
          RELEASE_KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: ./gradlew assembleRelease --stacktrace

      - name: Rename release APK
        if: steps.release_secrets.outputs.present == 'true'
        run: |
          mv app/build/outputs/apk/release/app-release.apk \
             "app/build/outputs/apk/release/Ledgerly-v${{ steps.app_version.outputs.version_name }}-build${{ github.run_number }}-release.apk"

      - name: Upload debug APK artifact
        if: (github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main' && steps.release_secrets.outputs.present != 'true'
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk

      - name: Create GitHub Release with signed APK
        if: steps.release_secrets.outputs.present == 'true'
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v${{ steps.app_version.outputs.version_name }}-build${{ github.run_number }}
          name: Ledgerly v${{ steps.app_version.outputs.version_name }} (build ${{ github.run_number }})
          files: app/build/outputs/apk/release/*.apk
          generate_release_notes: true
          body: |
            versionCode: ${{ steps.app_version.outputs.version_code }}
```

Note `version_code` is extracted and written into the release body. The updater
compares integers, never version-name strings — see below.

---

## Signing key

**Back up `release.keystore.jks` somewhere that survives a laptop failure.**

Losing it is worse here than for a normal app. Android treats a differently
signed APK as a different application: the update fails, and uninstalling to fix
it destroys the app's private storage — including the Keystore-wrapped master
key. Recovery would then require the passphrase plus a Firestore restore, and
before Phase 5 there is no Firestore restore.

Store: the `.jks` file, keystore password, key alias, key password. Offline.

---

## CI cannot validate Phase 0

Unit tests run in CI. These do **not**:

| Test | Why CI can't |
|---|---|
| Keystore hardware backing | runners have no TEE/StrongBox |
| `BiometricPrompt` flows | no biometric hardware |
| Argon2id timing | measures the runner, not the phone |
| Lock-on-background | needs real lifecycle |

`connectedAndroidTest` stays manual, on a real device. A green CI badge does not
mean Task 0.5 or 0.8 passed.

---

## Update checking

Poll the GitHub Releases API for the repo. Public repo -> no token needed
(60 req/hour unauthenticated, ample for a daily check).

```
GET https://api.github.com/repos/<owner>/ledgerly/releases/latest
```

**Rules:**

1. **Compare `versionCode` integers.** Never parse version-name strings. Read the
   remote code from the release body or an `assets` naming convention; compare to
   `BuildConfig.VERSION_CODE`. Greater -> update available.

2. **Release builds only.** Never offer debug artifacts as updates.

3. **Verify the signing certificate before installing.** Fetch the downloaded
   APK's signing certificate digest via `PackageManager.getPackageArchiveInfo`
   with `GET_SIGNING_CERTIFICATES`, and compare to the running app's own
   certificate digest. Mismatch -> refuse, delete the file, notify.
   This is the check that stands between a hijacked update channel and a finance
   app. Android's installer would reject the mismatch anyway, but failing early
   and loudly means the user finds out rather than seeing a silent install
   failure.

4. **User-initiated install.** `REQUEST_INSTALL_PACKAGES` + an explicit prompt.
   Never install silently.

5. **Never auto-update across a schema migration** without the user seeing a
   notice. A migration bug on a finance ledger is not a silent-background event.

6. **WorkManager, daily, `NetworkType.CONNECTED`, not expedited.** Update checks
   are never urgent.

7. Download to app-private cache; hand to the installer via `FileProvider`.
   Delete after install or failure.

---

## Test requirements

- `versionCode` comparison: equal, lower, higher, malformed
- Certificate mismatch refuses install and deletes the file
- Debug-tagged releases are ignored
- No network -> worker retries, no crash
- Failed download leaves no partial file in cache
