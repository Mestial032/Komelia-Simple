# Komelia Simple - Komga media client

> **This is a modified fork of [Komelia](https://github.com/Snd-R/Komelia) by Snd-R**, distributed under the same
> Apache 2.0 license. All credit for the original app goes to its author.
>
> **Este es un fork modificado de [Komelia](https://github.com/Snd-R/Komelia)**, con la misma licencia Apache 2.0.

### Download / Descarga

- Android APK: https://github.com/Mestial032/Komelia-Simple/releases (arm64 phones)
- If the official Komelia is installed, uninstall it first: both use the same app ID.  
  Si tienes la Komelia oficial instalada, desinstálala antes: usan el mismo identificador.
- A [Komga](https://komga.org) server is required.

### Changes in this fork / Cambios en este fork

All changes are in `komelia-ui` (shared by desktop and Android):

- **Home** only shows *Keep reading*: one book per series (the last one read), and tapping it resumes reading
  at the page where you left off.
- **Search bar** only lists series, not individual books.
- **Image reader**
    - Swipe left/right to change page in the paged reader, showing the next page while dragging.
    - Pure black (`#000000`) background around the pages, so OLED screens turn those pixels off.
- **Libraries**
    - The *Libraries* button opens the series directly.
    - *Edit → General* lists every library folder with its name, and new folders can be added there.
    - The *All libraries* view can scan every library at once.
    - New libraries empty the trash automatically after each scan.
    - Faster loading: series are requested before the filter options, which load in parallel.

### Original Komelia downloads:

- Latest prebuilt release is available at https://github.com/Snd-R/Komelia/releases
- Google Play Store https://play.google.com/store/apps/details?id=io.github.snd_r.komelia
- F-Droid https://f-droid.org/packages/io.github.snd_r.komelia/
- AUR package https://aur.archlinux.org/packages/komelia

## Translations
Translations are managed by the original Komelia project. You can help translate Komelia to your language by using
the service provided by [Weblate](https://hosted.weblate.org/engage/komelia/); texts added only in this fork are
in English.

[![Translation status](https://hosted.weblate.org/widget/komelia/horizontal-auto.svg)](https://hosted.weblate.org/engage/komelia/)

## Build instructions
Make sure you download all git submodules\
`git clone --recurse-submodules https://github.com/Mestial032/Komelia-Simple` \
if you already cloned repository without recurse command run\
`git submodule update --init --recursive`

Requires jdk 17 or higher\
Android and JVM targets require C and C++ compiler for native libraries and Node.js for epub readers build.\
Recommended way to build is by using docker images that contain all required build dependencies.\
If you want to build with system toolchain and dependencies try running:\
`./gradlew komeliaBuildNonJvmDependencies` (Linux Only)

## Desktop App
Replace <*platform*> placeholder with your target platform. \
Available platforms include: `linux-x86_64`, `windows-x86_64`

- `docker build -t komelia-build-<platform> . -f ./cmake/<platform>.Dockerfile `
- `docker run -v .:/build komelia-build-<platform>`
- `./gradlew <platform>_copyJniLibs`
- `./gradlew buildEpubReaders`

Then choose your packaging option:
- `./gradlew :desktopRun` to launch desktop app
- `./gradlew :desktopJar` output in `./komelia-app/desktopApp/build/compose/jars`
- `./gradlew :desktopDeb` output in `./komelia-app/desktopApp/build/compose/binaries`
- `./gradlew :desktopMsi` output in `./komelia-app/desktopApp/build/compose/binaries`

## Android App
Replace <*arch*> placeholder with your target architecture.\
Available architectures include:  `aarch64`, `armv7a`, `x86_64`, `x86`

- `docker build -t komelia-build-android . -f ./cmake/android.Dockerfile `
- `docker run -v .:/build komelia-build-android <arch>`
- `./gradlew <arch>_copyJniLibs`
- `./gradlew buildEpubReaders`

Then choose app build option:

- `./gradlew :androidDebug` output in `./komelia-app/androidApp/build/outputs/apk/debug`
- `./gradlew :androidRelease` output in `./komelia-app/androidApp/build/outputs/apk/release`


## Komf Wasm WebUI
run `./gradlew :komfWebUI` output will be in `./build/komf-webui`

## Komf Wasm Extension
for chrome `./gradlew :komfExtensionChrome` \
for firefox `./gradlew :komfExtensionFirefox` \
output archive will be in `./komelia-komf-extension/app/build/distributions`