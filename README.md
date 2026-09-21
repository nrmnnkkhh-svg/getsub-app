# GetSub — Termux YouTube Subtitle Downloader

[![CI](https://github.com/nrmnnkkhh-svg/getsub-app/actions/workflows/ci.yml/badge.svg)](https://github.com/nrmnnkkhh-svg/getsub-app/actions/workflows/ci.yml)

Custom Android + Termux utility that downloads YouTube subtitles and reformats them into clean, readable plain-text (`.txt`) files in the Android Download folder.

Three entry points share one goal:
1. **CLI:** `getsub <url> [lang]` — bash + yt-dlp + Python engine in `$PREFIX/bin/getsub`.
2. **Share → Termux:** `~/bin/termux-url-opener` handles Android share intents.
3. **Share → GetSub app:** `com.getsub.share` — standalone Android app (built 100% in Termux) with its own Java fetch engine (`SubtitleFetcher`), foreground service (`SubtitleDownloadService`), and home-screen job list (`MainActivity` + `JobStore`).

## Build

Built on-device with `aapt2` / `javac` / `dx` / `apksigner`:

```bash
cd ~/getsub-app && bash build.sh
cp build/apk/app.apk ~/storage/downloads/getsub-app.apk
termux-open ~/storage/downloads/getsub-app.apk
```

Requirements: `openjdk-21`, `aapt`, `aapt2`, `apksigner`, `dx`, `zip`, `unzip`, `curl`, `android.jar` (platform-33) in `~/android-sdk/`, signing key in `~/.debug.keystore`.

## Layout

| Path | Purpose |
|---|---|
| `AndroidManifest.xml` | App manifest (standalone, no Termux dependency at runtime) |
| `build.sh` | Full on-device build + sign pipeline |
| `res/values/strings.xml` | App name resources |
| `res/drawable/ic_launcher.png` | Custom launcher icon (brand purple, generated) |
| `src/com/getsub/share/` | `ShareActivity`, `MainActivity`, `JobStore`, `SubtitleFetcher`, `SubtitleDownloadService` |
| `build/apk/app.apk` | Built, signed APK |
| `PROJECT_CAPSULE.md` | Full project memory: history, source, design, troubleshooting |
| `getsub-app-full-source.md` | Generated full-source snapshot (regenerated on every change per the rule below) |
| `.github/workflows/ci.yml` | GitHub Actions CI: build + parser suite + emulator UI tests on every push |
| `ci/` | CI scripts: `run_parser_tests.sh` (suite + golden diff), `emulator_ui_test.py/.sh` (adb-driven UI tests) |
| `tests/TestParsers.java` | 40-check synthetic battery for `SubtitleFetcher` parsing/formatting |

## AI Maintenance Rule (mandatory)

The `PROJECT_CAPSULE.md` file is the canonical memory for this project.

Any AI working on this project **must**, every time any change is applied (code, manifest, build, docs, behavior, or fix):

1. **Update `PROJECT_CAPSULE.md`** to reflect the change — new/edited files, new version or changelog entry, updated file map, and any new troubleshooting notes.
2. **Save the updated capsule to the phone's Download folder** — copy it to `~/storage/downloads/PROJECT_CAPSULE.md` every time, so the latest memory is always available outside the project folder.
3. **Save a fresh full-source MD** — regenerate `~/storage/downloads/getsub-app-full-source.md` (all 8 source files, fenced code blocks) so the rebuildable snapshot never goes stale.
4. **Report the changes** to the user in the reply — what changed, why, files touched, verification done, and what the user needs to do next (rebuild, reinstall, grant permission, etc.).

Do not skip the capsule update, the Download-folder copies, or the full-source export. A code change without all three is an incomplete task.
