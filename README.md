# Perchance for Android

A tiny, standalone Android app that opens [perchance.org](https://perchance.org) in its own
window, with no browser chrome, no address bar and no tabs. It is a `WebView` wrapper done
properly: accounts and generator saves persist, file uploads work, downloads land in
`Downloads/`, and everything a generator can do in Chrome it can do here.

```
Perchance/
├── settings.gradle.kts            Gradle project + repositories
├── build.gradle.kts               AGP / Kotlin plugin versions
├── gradle.properties
├── gradle/wrapper/                Gradle 8.11.1 wrapper (jar included)
├── gradlew, gradlew.bat
├── icon-source.svg                Source artwork for the launcher icon
├── .github/workflows/build-apk.yml  CI: builds installable APKs on push
├── scripts/build-apk.sh           One-command build on Linux / Colab / Termux
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/org/perchance/app/
        │   ├── PerchanceApp.kt        Application: follows the system light/dark setting
        │   └── MainActivity.kt        Everything: WebView setup, downloads, uploads, chrome
        └── res/
            ├── layout/activity_main.xml   Toolbar + progress bar + WebView + error screen
            ├── menu/main_menu.xml         Refresh / Home / Share / Open in browser / Clear data
            ├── drawable/                  Material icons + adaptive launcher icon layers
            ├── mipmap-*/                  Legacy launcher PNGs (48…192 px, square + round)
            ├── values*/                   Strings, brand colours, light + dark themes, splash
            └── xml/file_paths.xml         FileProvider paths (uploads, camera, downloads)
```

## Building it

**JDK 17** and the **Android SDK** (API 35 platform + build-tools) are required for the local
options (A, B and E). Options C and D install nothing on your machine.

A zip of this whole project is mirrored at <https://user.uploads.dev/file/12c1e09b7e6e3f025e2942bcdb27dd3c.zip> — that is what the GitHub
Actions bootstrap (Option C) and the one-command build (Option D) download.

### Option A — Android Studio (recommended)

1. Install [Android Studio](https://developer.android.com/studio) (it bundles the JDK and SDK).
2. `File ▸ Open…` and select this folder.
3. Let Gradle sync, then `Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)`.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy it to your phone (USB, Drive, whatever), tap it, and allow "install unknown apps"
   for whichever app you used to open it.

Building locally is the nicest workflow because Android Studio keeps the same debug signing
key on your machine, so every new build installs **over** the previous one and keeps your
Perchance logins and saved generators.

### Option B — command line

```bash
chmod +x gradlew          # if the archive lost the executable bit
./gradlew assembleDebug   # or: ./gradlew installDebug   (with a phone plugged in)
```

`local.properties` needs `sdk.dir=/path/to/Android/sdk` if `ANDROID_HOME` is not set.

### Option C — GitHub Actions, nothing installed locally (works from a phone)

This is the way to get an installable APK without touching a computer:

1. Create an empty repository: <https://github.com/new> → name it → **Create repository**.
2. In the repo: **Add file ▸ Create new file**, type `.github/workflows/build-apk.yml` as the
   file name, paste the bootstrap workflow (the copy handed to you alongside this project),
   and commit it. That commit starts the build.
3. **Actions** tab → wait ~3 minutes for the green tick.
4. **Releases** (right-hand side of the repo home page) → download **`Perchance.apk`** → tap it
   on your phone → allow "install unknown apps" for whichever app opened it.

The run's *Artifacts* section also holds `perchance-apk` (both APKs, zipped), so you have two
ways to fetch them.

Two things worth knowing about what the workflow does:

- On the first run it writes the app **source** and a freshly generated signing key
  (`signing/perchance.jks`) into the repository. Every later build is signed with that same
  key, so new APKs install as an **update** over the old ones and keep your Perchance logins
  and saved generators. Delete that file if you ever want a different identity.
- If the repo does not contain the source yet (a brand-new repo, where the workflow's
  `PROJECT_ZIP_URL` points at the mirror of this project), it downloads it first. After an
  upstream change, re-download it with **Actions ▸ Run workflow ▸ resync_source**.

`PROJECT_ZIP_URL` is not limited to a zip link — anything that resolves to the project works:

- a git repository URL — `https://github.com/<you>/<repo>.git` (cloned with `--depth 1`)
- a repository archive — `https://github.com/<you>/<repo>/archive/refs/heads/main.zip`, the
  equivalent GitLab/Bitbucket download link, or a `.tar.gz`/`.tgz` tarball (the enclosing
  `<repo>-<branch>/` folder is detected and flattened automatically)
- the project zip mirror of this generator

So you can keep the source in a repo of your own and point the workflow at it; the workflow
copies it in (dropping the source's `.git`, and never overwriting the signing key that already
lives in the target repo). Once the source is committed in the repo the URL is ignored until
you tick `resync_source` again — from then on **the repo is the project**, and that repo's
`.../archive/refs/heads/main.zip` / `.git` URL is a permanent link you can point elsewhere.

That generated key is a throwaway debug identity stored in plain sight (as is everything in a
public repo) — perfect for sideloading your own builds, not for publishing the app. Use your
own private keystore for that; see *Option E*.

### Option D — one command on Linux, in Colab, or on the phone itself

`scripts/build-apk.sh` installs JDK 17 + the Android SDK itself and builds the app:

```bash
bash scripts/build-apk.sh                 # build this checkout
bash scripts/build-apk.sh <project-zip>   # or download the project first
```

In **Google Colab** (a free Linux VM in your browser, straight to your Downloads):

```python
!curl -sL <build-apk.sh-url> | bash
from google.colab import files; files.download('/root/perchance-build/project/app/build/outputs/apk/debug/app-debug.apk')
```

On **Termux** the same script installs `openjdk-17`, `gradle` and `aapt2` with `pkg`, points
Gradle at Termux's native aapt2 (the one inside build-tools is x86-only and will not run on a
phone), and copies the result to `~/storage/downloads/Perchance.apk`. Budget ~2 GB of
downloads and a slow first build.

### Option E — using your own signing key

Release signing is driven by the environment variables `PERCHANCE_KEYSTORE`,
`PERCHANCE_KEYSTORE_PASSWORD`, `PERCHANCE_KEY_ALIAS` and `PERCHANCE_KEY_PASSWORD` (or the
matching `perchance.keystore*` Gradle properties). Without them `assembleRelease` still runs
but emits an **unsigned** APK, which Android refuses to install.

```bash
keytool -genkeypair -v -keystore release.jks -alias perchance \
        -keyalg RSA -keysize 2048 -validity 10000
```

Then export those four variables, or store the base64 of `release.jks` as a repository secret
and replace the workflow's *Create a signing key* step with one that decodes the secret into
`signing/perchance.jks`.

## What the app does

- **Own window, no browser UI.** Home is `https://perchance.org/`; a slim toolbar shows the
  page title and offers Refresh, Home, Share, Open in browser and Clear cookies/site data.
- **Stays logged in.** Cookies, DOM storage, IndexedDB and the WebView cache persist between
  launches. Third-party cookies are explicitly accepted because every generator actually runs
  inside an iframe on its own `<generatorPublicId>.perchance.org` subdomain — without that,
  accounts, `kv-plugin` saves and the image gallery stop working.
- **Deep links.** `https://perchance.org/anything` links can open straight into the app
  (Android will ask which app to use). `launchMode="singleTask"` means existing sessions are
  reused instead of stacking new activities.
- **File uploads.** Generators that ask for a file (`<input type="file">`, `upload-plugin`,
  comment avatars…) get the real system picker, including multi-select and a camera option
  for image inputs.
- **Downloads work.** Regular downloads go through `DownloadManager` (with the page's cookies
  and user agent, so logged-in downloads work) and land in `Downloads/`. Blob and `data:`
  URLs — the way in-page generators export text, canvas images and recordings — are read in
  the page and written to `Downloads/` via MediaStore (pre-Android 10: the app's own files dir
  exposed through a `FileProvider`). A snackbar offers to open what was just saved.
- **Back button behaves like a browser.** Back goes through page history, then returns to the
  Perchance home page, and only then asks for a second press to exit.
- **Fullscreen video** (HTML5 `<video>`) gets real immersive fullscreen.
- **Recovers from renderer crashes.** If the sandboxed renderer process is killed (usually
  OOM inside a heavy generator), the app offers to rebuild and reload instead of dying.
- **Error screen** with a retry button when a main-frame load fails.
- **Light/dark** follows the system, and the system bars are tinted to match.

### Deliberately withheld

- **Camera and microphone access for web pages.** `onPermissionRequest` denies
  `getUserMedia`; the app never asks for `CAMERA`/`RECORD_AUDIO`, so pages cannot stream
  from your device. This is the main deliberate difference from Chrome/Twitter-style wrappers.
- **Ignoring TLS errors.** A `WebViewClient.onReceivedSslError` override is *not* present, so
  bad certificates fail loudly rather than silently.
- **The JS bridge is origin-locked.** The only `@JavascriptInterface` method
  (`WebBridge.saveBase64`, used for blob downloads) refuses to run unless the current page is
  on a `*.perchance.org` host.

## Customising

| Want to change… | Where |
| --- | --- |
| Start page | `HOME_URL` in `MainActivity.kt` |
| Dark-webpage auto-darkening | `ALLOW_ALGORITHMIC_DARKENING` in `MainActivity.kt` |
| Brand colour | `res/values/colors.xml` + `res/values-night/colors.xml` |
| App name | `app_name` in `res/values/strings.xml` |
| Launcher icon | `res/drawable/ic_launcher_*.xml` (adaptive, API 26+) and the `res/mipmap-*` PNGs (legacy). `icon-source.svg` is the original artwork the PNGs are drawn from. |
| Grant camera/microphone to pages | add `<uses-permission>` entries, then override `onPermissionRequest` in `MainActivity.kt` |

## Notes and limitations

- `minSdk` is 24 (Android 7.0), `targetSdk`/`compileSdk` are 35. It targets modern edge-to-edge
  layout, so insets are handled with `WindowInsetsCompat` rather than pad-everything hacks.
- DRM-protected video and Web Push notifications won't work — the WebView doesn't provide them.
- Some generators use APIs Chrome supports but Android's WebView does not (e.g. certain WebGPU
  features on older devices). Update "Android System WebView" from the Play Store for the best
  compatibility — the app always uses the system WebView engine.
- `largeHeap="true"` is set because generators can allocate big canvases/typo arrays, but a
  genuinely runaway page will still hit the renderer's own memory cap; that is what the
  renderer-crash recovery path is for.
- When a signing keystore is configured (see *Option E*) the `debug` build is signed with it
  too, so a CI-built APK installs over a later one without uninstalling. Android Studio then
  asks you to uninstall an older build that was signed with the IDE's default debug key.
