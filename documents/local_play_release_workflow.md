# Local Play Release Workflow

This branch is Kevin's personal Play Store upload branch.

Keep `main` as a clean mirror of the original repository's `upstream/main`.
Keep Play Store, package-id, signing, release-note, and version-code changes on
the personal branch.

## Branch Roles

- `main`: vanilla upstream code. Pull original repo updates here.
- `krushin/aaos14-vcu-fixes`: current personal branch — Play upload setup plus the
  GM VCU / AAOS 14 platform fixes (revision [147]).
- `codex/kevin-play-release`: SUPERSEDED. Based on release 140 and never rebased, so
  it is missing revisions 141-145. Kept for reference only; do not build from it.
- `backup/pre-upstream-reset-20260605`: old fork state retained for reference.

Unlike the superseded branch, the current one is not upload-config-only — it also
carries runtime fixes for the VCU platform, because upstream targets gminfo3.7 and
has no VCU hardware to test against.

## Pull Latest Upstream

From a clean working tree:

```bash
git switch main
git pull --ff-only upstream main

git switch krushin/aaos14-vcu-fixes
git rebase upstream/main
```

Resolve any rebase conflicts by keeping upstream runtime code unless the conflict is
in local Play upload setup or in a VCU platform fix that upstream cannot test.

## Before Building

Use a new `versionCode` for every Play upload. Update both:

- `app/build.gradle.kts`
- `documents/revisions.txt`

Do not commit service-account JSON files, keystore files, or passwords.

Expected local credential locations:

```text
~/.config/carlink/play-service-account.json
key.properties
```

`key.properties` should point to a keystore outside git or to a local ignored
file. The Play service-account JSON must stay outside the repository.

Both are already covered by `.gitignore` (`**/key.properties`, `*.jks`). The `play {}`
block in `app/build.gradle.kts` only sets `serviceAccountCredentials` when the JSON is
actually present, so `bundlePlayRelease` works on a machine without credentials — only
the `publish*` tasks need them.

## Verify Before Upload

```bash
jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab
```

Expect `jar verified.` The trailing PKIX "certificate chain is invalid" warning is
normal for a self-signed upload keystore and is not a failure.

Then confirm the merged manifest carries the fork identity, not upstream's:

```bash
MF=app/build/intermediates/merged_manifest/playRelease/processPlayReleaseMainManifest/AndroidManifest.xml
grep -oE 'package="[^"]*"|android:authorities="[^"]*"' "$MF"
```

Expected: package `com.krushin.carplay`, and authorities
`com.krushin.carplay.ClusterIconContentProvider`, `com.krushin.carplay.albumart`,
`com.krushin.carplay.androidx-startup`. The GM Templates Host authority must NOT
appear — Play rejects bundles claiming an authority owned by another developer.

Note: `zeno.carlink` still legitimately appears in the manifest as a `taskAffinity`
string and as the `zeno.carlink.ipc.NaviVideoSourceService` class name (a real source
package, part of the AIDL contract with ClusterHomeDisplay). Neither is a provider
authority, so neither blocks upload.

## Build

```bash
./gradlew :app:bundlePlayRelease
```

Expected artifact:

```text
app/build/outputs/bundle/playRelease/app-play-release.aab
```

## Upload Draft To Android Automotive Internal Testing

```bash
./gradlew :app:publishPlayReleaseBundle \
  --track automotive:qa \
  --release-status draft \
  --release-name "1.0.0 (<versionCode>)"
```

Use the `automotive:qa` track. Do not upload this app to the generic internal
testing track because the bundle requires Android Automotive OS.

## Manual Play Console Upload

If CLI credentials are missing, upload this file manually in Play Console:

```text
app/build/outputs/bundle/playRelease/app-play-release.aab
```

Use the Android Automotive OS dedicated internal testing track and create a draft
release unless an intentional rollout was explicitly requested.
