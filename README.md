# Rapid Office Pro

A fast Android reader for PDF, Word, Excel, PowerPoint, CSV and text files.

## Install

Download the latest `.apk` from the [Releases](../../releases/latest) page and open it on the phone.
After that, the app updates itself: **⋮ → Install latest version**, or **Settings → Install latest
version**.

Full feature notes: [`apk/README.txt`](apk/README.txt).

## Publishing a new version

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build the signed APK: `./gradlew :app:assembleRelease`.
3. Create a GitHub release tagged `v<versionName>` (for example `v2.5`) and attach
   `app/build/outputs/apk/release/app-release.apk`, renamed to `RapidOfficePro-v<versionName>.apk`.

Phones running the app find the release through the GitHub API. It must be marked as the
**latest** release and have an `.apk` attached.

The signing key (`keystore/`, `keystore.properties`) is deliberately not in this repository. Keep
a backup of it: an update signed with any other key cannot install over the existing app.
