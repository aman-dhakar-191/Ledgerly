# Task — Update system

Belongs in **Phase 1**, after the app is installable. Not Phase 0.

Adapted from the Glimpse `UpdateCheckWorker`. That structure is sound and is
reused: notify-once-per-`versionCode`, `ExistingPeriodicWorkPolicy.KEEP` so
repeated `schedule()` calls don't reset the timer, and a `checkNow()` one-off
because a periodic request's first run can be delayed by hours.

Read `docs/ci.md` before starting.

---

## What changes from Glimpse

| Glimpse | Ledgerly | Why |
|---|---|---|
| `FirebaseAuth.currentUser == null` gate | removed | no Firebase before Phase 5 |
| no `Constraints` | `NetworkType.CONNECTED` | otherwise it fires offline and wastes the run |
| notification text is free-form | never contains financial data | lock-screen previews |
| install path unspecified | signature verification required | this is a finance app |

---

## UpdateChecker

```kotlin
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val sizeBytes: Long
)

interface UpdateChecker {
    suspend fun checkForUpdate(): UpdateInfo?   // null = up to date
}
```

`GET https://api.github.com/repos/<owner>/ledgerly/releases/latest`

- Unauthenticated. Public repo, 60 req/hour, a daily check is nowhere near it.
- Parse `versionCode` from the release body (CI writes `versionCode: N` — see
  `docs/ci.md`). **Never parse the version-name string.** Compare integers
  against `BuildConfig.VERSION_CODE`.
- Malformed or missing `versionCode` -> return null, log, do not guess.
- Ignore prereleases and drafts.
- Asset must be the `-release.apk`. **Never offer a debug artifact.**
- Any network failure -> `Result.retry()`, not a crash.

---

## UpdateCheckWorker

Keep the Glimpse shape. Changes:

```kotlin
fun schedule(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}
```

- Remove the FirebaseAuth check entirely
- Keep the `last_notified_version_code` dedupe — without it the same release
  re-notifies daily until installed
- Keep `checkNow()` for app open
- Not expedited. Update checks are never urgent.
- Notification text: app name and version only. **No financial data in any
  notification, ever** — this rule applies to every notification Ledgerly will
  ever post, not just this one.

---

## Installer — the part Glimpse doesn't cover

```kotlin
interface UpdateInstaller {
    suspend fun download(info: UpdateInfo): Result<File>
    fun verifySignature(apk: File): Boolean
    fun requestInstall(apk: File)
}
```

### Download
- App-private cache dir only
- Verify `Content-Length` matches `info.sizeBytes`
- Partial or failed download -> delete the file, return failure. Never leave a
  half-written APK on disk.

### Signature verification — required before any install prompt

```kotlin
val remote = packageManager.getPackageArchiveInfo(
    apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
)?.signingInfo?.apkContentsSigners
val local = packageManager.getPackageInfo(
    packageName, PackageManager.GET_SIGNING_CERTIFICATES
).signingInfo.apkContentsSigners
// compare SHA-256 digests
```

Mismatch -> delete the APK, do not prompt, notify the user that an update was
rejected.

Android's package installer would reject a mismatched signature anyway. Doing it
here means the user is *told*, rather than seeing an install that silently fails.
For an app holding a full financial history, a hijacked update channel should be
loud.

### Install
- `REQUEST_INSTALL_PACKAGES` permission
- `FileProvider` URI, `ACTION_VIEW` with
  `application/vnd.android.package-archive`
- Explicit user tap. **Never silent.**
- Delete the cached APK after install or failure

---

## Schema migration guard

If the pending update crosses a Room schema version, the update screen must say
so plainly before the user installs. A migration bug on a finance ledger is not
a silent background event.

[Implementation note: expose the target schema version in the release body
alongside `versionCode`, or gate on a `migration: true` marker.]

---

## Tests

- `versionCode` comparison: equal, lower, higher, absent, malformed
- Prerelease and draft releases ignored
- Debug-named assets never returned
- Signature mismatch refuses install and deletes the file
- Size mismatch deletes the partial file
- No network -> retry, no crash
- Same `versionCode` notifies exactly once across repeated runs
- `schedule()` called repeatedly does not reset the periodic timer
