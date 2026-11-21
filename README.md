# Rapid Office Pro

A fast Android reader for PDF, Word, Excel, PowerPoint, CSV and text files.

## Install

Download the latest `.apk` from the [Releases](../../releases/latest) page and open it on the phone.
After that, the app updates itself: **⋮ → Install latest version**, or **Settings → Install latest
version**.

Highlights:

- **Share pages**: tap **Share** in the reading toolbar, pick pages by tapping or with
  **Batch select** (`20-17, 24, 56-59`), name the new PDF, then share or save it.
- Opens PDF, Word, Excel, PowerPoint, OpenDocument, e-books, CSV, web pages and text by content.
- Stays smooth on huge scanned PDFs: memory is given back instead of piling up.

Full feature notes: [`apk/README.txt`](apk/README.txt).

## Publishing a new version

1. Raise `versionCode` by one and set `versionName` in `app/build.gradle.kts`.
2. Build the signed APK: `./gradlew :app:assembleRelease`.
3. Create a GitHub release tagged `v<versionName>` (for example `v1.2`) and attach
   `app/build/outputs/apk/release/app-release.apk`, renamed to
   `RapidOfficePro-v<versionName>-b<versionCode>.apk` (for example `RapidOfficePro-v1.2-b14.apk`).

The `-b<versionCode>` part is how phones tell a newer release from an older one. The version
*name* was reset to 1.0 after 2.6, so names alone no longer sort; the build number always goes up.
A release without it is treated as older than 1.0.

Phones running the app find the release through the GitHub API. It must be marked as the
**latest** release and have an `.apk` attached.

The signing key (`keystore/`, `keystore.properties`) is deliberately not in this repository. Keep
a backup of it: an update signed with any other key cannot install over the existing app.
