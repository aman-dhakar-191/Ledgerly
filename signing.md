# Signing key

The release signing key is the single most unrecoverable artifact in this
project. Read this before touching it.

---

## Why it matters more here than for a normal app

Android identifies an app by `(packageName, signingCertificate)`. Change the
certificate and it is a different app:

1. Installing the new APK over the old one **fails**
2. The only fix is uninstall, which **deletes app-private storage**
3. That storage holds the Keystore-wrapped master key and the entire local Room
   database

Before Phase 5 there is no cloud backup. Losing the signing key before then means
losing the ledger. After Phase 5, recovery requires the passphrase and a full
Firestore restore.

**The signing key is not the same thing as the master key.** The signing key
proves the APK came from you. The master key encrypts your data. They are
unrelated systems — but losing the signing key destroys the master key as
collateral damage, via the uninstall.

---

## Generation

Run once. Never again.

**Windows (PowerShell)**
```powershell
keytool -genkeypair -v `
  -keystore ledgerly-release.jks `
  -alias ledgerly `
  -keyalg RSA -keysize 4096 `
  -validity 10000 `
  -dname "CN=Ledgerly, O=Personal, C=IN"
```

**macOS / Linux**
```bash
keytool -genkeypair -v \
  -keystore ledgerly-release.jks \
  -alias ledgerly \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Ledgerly, O=Personal, C=IN"
```

Modern JDKs default to PKCS12, where the key password equals the store password.
`keytool` will not prompt separately for a key password — this is expected, not
an error.

`-validity 10000` is about 27 years. Longer is harmless but pointless: **Android
does not check certificate expiry after installation.** An expired certificate
does not break an installed app or block updates signed with the same key. Expiry
only matters for Play Store submission, which does not apply to a sideloaded app.

---

## Loading into GitHub secrets

**Windows (PowerShell)**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ledgerly-release.jks")) |
  Set-Content -NoNewline ledgerly-release.jks.b64
```

**macOS / Linux**
```bash
base64 -w 0 ledgerly-release.jks > ledgerly-release.jks.b64   # Linux
base64 -i ledgerly-release.jks -o ledgerly-release.jks.b64    # macOS
```

Single-line output is required. A wrapped or newline-terminated string breaks
`base64 -d` in the workflow.

```bash
gh secret set RELEASE_KEYSTORE_BASE64 < ledgerly-release.jks.b64
gh secret set RELEASE_KEYSTORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS          # ledgerly
gh secret set RELEASE_KEY_PASSWORD       # same as store password
```

Verify with `gh secret list`, then delete the `.b64` file. It is a transport
artifact, not a backup.

---

## Backup

Store offline, in at least two places that do not fail together:

- `ledgerly-release.jks`
- store password
- key alias (`ledgerly`)
- key password (same as store password)

The `.jks` must never enter the repository. `.gitignore`:

```
*.jks
*.keystore
*.b64
```

A password manager attachment plus an encrypted USB or printed copy is
sufficient. "It's on my laptop" is not a backup.

---

## Verifying an APK's signature

Confirm a build was signed with the expected key:

```bash
keytool -printcert -jarfile Ledgerly-v0.1.0-build12-release.apk
```

Compare the SHA-256 fingerprint against the keystore's:

```bash
keytool -list -v -keystore ledgerly-release.jks -alias ledgerly
```

The in-app updater performs this comparison automatically before installing —
see `tasks/update-system.md`.

---

## Rotation

**Assume rotation is impossible. Plan accordingly.**

There is a mechanism — APK Signature Scheme v3 proof-of-rotation, via
`apksigner --lineage` — but it requires **both the old and new keys present at
rotation time**. It handles retiring a key you still hold. It does nothing for a
key you have lost, which is the only scenario where anyone actually wants
rotation.

If rotation ever becomes necessary and the old key is still available:

```bash
apksigner rotate --in old-lineage.bin --out new-lineage.bin \
  --old-signer --ks ledgerly-release.jks \
  --new-signer --ks ledgerly-release-v2.jks
```

Then sign every future release with `--lineage new-lineage.bin`. The lineage file
becomes as critical as the keys and must be backed up alongside them.

Untested in this project. Treat as a last resort, not a plan.

---

## If the key is lost

There is no clean recovery. In order of preference:

1. **Before Phase 5** — export data first if the app still runs
   (Settings → Export), then uninstall, install the newly signed build, and
   re-import. The export is the only thing that survives.
2. **After Phase 5** — uninstall, install the newly signed build, sign in, enter
   the passphrase, restore from Firestore. Data survives; local state does not.
3. **Neither** — the data is gone.

This is why the backup section above is not optional.
