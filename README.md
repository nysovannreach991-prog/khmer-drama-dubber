# Khmer Drama Dubber (Android)

Put a **video** and its **.srt** subtitle file in a folder, run one command, and get the video back
**dubbed in Khmer**:

1. Reads the .srt (Chinese, English, Thai, Korean… or already Khmer)
2. Translates every line to Khmer (Google Translate, free, no key). This is skipped if the .srt is already Khmer
3. Speaks each line with **edge-tts** Khmer voices: `km-KH-SreymomNeural` (female) / `km-KH-PisethNeural` (male)
4. Speeds up lines that are too long so they fit their subtitle time
5. Mixes the Khmer voice into the video. The original music/sound stays quietly in the background
   and is lowered further while the Khmer voice speaks

You also get a Khmer `.srt` file next to the dubbed video.

There are two versions. Use the **app** unless you have a reason not to.

| | Android app (APK) | Termux script (`khmer_dubber.py`) |
|---|---|---|
| Install | Tap the APK | Termux + `install.sh` |
| Use | Tap buttons | Type `khmerdub` |
| Long lines | Re-spoken faster by edge-tts (sounds natural) | Sped up with ffmpeg |
| Needs | Android 8.0+ and internet | Termux, Python, ffmpeg and internet |

---

# Android app

## Install
1. Download the newest APK from
   https://github.com/nysovannreach991-prog/khmer-drama-dubber/releases/latest
   on the phone (or copy it over with Telegram, Google Drive, USB…) and tap it.
2. If Android asks, allow **"Install unknown apps"** for the app you opened it from.
3. Android may warn that the app is from an unknown developer. Tap **Install anyway** (it's your own app).

## Use
The first time you open the app, a short Khmer guide (steps ១–៦) shows you what to tap.
You can open it again from **⚙ Settings → របៀបប្រើ · How to use**.

1. Tap **Choose video**, then **Choose subtitle (.srt)**. Each button only shows that kind of file.
   For many episodes, pick several of each with matching names (`ep01.mp4` + `ep01.srt`).
2. Choose the voice, **Sreymom ♀** or **Piseth ♂**. ▶ plays a sample.
3. Tap **Start dubbing**. You can leave the app; progress shows in the notification.
4. Results:
   - Dubbed videos: **Movies/KhmerDubber** (they show up in the Gallery)
   - Khmer subtitles: **Download/KhmerDubber**

Everything else is in **⚙ Settings** (top right): speed, pitch, voice volume, fitting long lines,
original sound, translate, skip songs, and **Lines made at once**. It makes 16 lines at the same
time by default, which is the fastest. Lower it if your internet is slow or lines fail.

### Logo & Lower third
Tap the **Logo & Lower third** card to put your brand on every dubbed video:
- **Logo**: choose a picture (PNG with a see-through background looks best) or an animated logo video
  (MP4 or MOV on a green or blue screen; it loops). Set its size and opacity, or make a picture round.
- **Lower third**: type your page name (Khmer works) and an optional second line, then pick a colour.
  You can also use your own picture made in Canva, or a **video** lower third (MP4 or MOV on a green or
  blue screen, like the free "lower third green screen" packs). It shows every few minutes (you choose
  how often), or stays on all the time.
- **Landscape 16:9 / Vertical 9:16**: each shape keeps its own places and sizes. Every video uses the one
  that fits it.
- **Position**: tap a spot on the 3×3 pad, or drag the logo or lower third in the preview.
- **Green / blue screen videos**: the background colour is found and removed by itself, and the empty
  part of the picture is cut away, so a full 1920×1080 pack becomes just the graphic. If some
  background is left, raise **Strength**; if parts of the graphic disappear, lower it or pick the other
  colour. **None** keeps the whole video picture. Logos are kept up to 10 s, lower thirds up to 20 s.
  Videos with a real see-through background (ProRes 4444 .mov) can't be read on phones; export them on a
  green background instead.

With a logo or lower third, saving takes longer because the picture is re-made (46 s for a
2-minute 720p video on the Mac's test phone; real phones with a hardware encoder are usually faster). If a phone can't re-make a video, it's saved without them and
the log says why.

**Stop** stops after the current step. **Start** again continues where it stopped
(translation and finished voice lines are kept).

The `[M]`/`[F]` tags and the "fix a bad translation" tip below work in the app too.

## Updates from GitHub
The app checks **https://github.com/nysovannreach991-prog/khmer-drama-dubber/releases** every
time it opens (and with the **Check for updates** button). When there's a newer version it shows
**Update now**. The first time, Android asks you to allow "Install unknown apps" for Khmer Dubber.

**To publish an update** (after changing the code):
```bash
./release.sh 1.2 "What's new in this version"
```
This sets the version, commits, tags `v1.2` and pushes. GitHub Actions then tests the code,
builds the signed APK and creates the release (about 2 minutes). Phones see it next time
they open the app. Watch the build at
https://github.com/nysovannreach991-prog/khmer-drama-dubber/actions

The signing key is stored on GitHub as encrypted secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`).
**Also keep `android-app/khmerdubber.jks` and `keystore.properties` safe on your computer.** Android
only installs an update over the old app if it's signed with the same key.

## Google Play
The app is built two ways from the same code:
- **github**: the sideloaded APK that updates itself from GitHub Releases (`./release.sh`).
- **play**: for Google Play. No self-updater (Play doesn't allow it); Play installs updates.

`./play-bundle.sh` builds the Play upload file `KhmerDubber-play-vX.Y.aab`.
Everything needed to publish (store texts, graphics, form answers, privacy policy) is in
[`play-store/`](play-store/PUBLISHING.md).

## Preview on this Mac
Double-click **`Preview on Mac.command`**. It opens a virtual Android phone with Khmer Dubber.
Test videos are already in its Download folder.

## Build by hand (on this Mac)
```bash
cd android-app
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
```

If Microsoft changes the edge-tts service and voice lines start failing, compare
`EdgeDrm.kt` / `EdgeTts.kt` with the latest Python `edge-tts` (`drm.py`, `constants.py`).

---

# Termux script

## Install on the phone (one time)

1. Install **Termux** from **F-Droid** or **GitHub** (https://github.com/termux/termux-app/releases).
   *Don't use the Play Store version. It is old and won't work.*
2. Copy the files `khmer_dubber.py` and `install.sh` to the phone, into
   `Download/khmer-dubber/`
3. Open Termux and type:

   ```bash
   termux-setup-storage
   ```
   Tap **Allow**. Then:

   ```bash
   bash /sdcard/Download/khmer-dubber/install.sh
   ```

That's it. The installer sets up Python, ffmpeg and edge-tts, and creates the folder
`Internal storage/KhmerDubber/`.

## Use

1. Put the video and the .srt in **`Internal storage/KhmerDubber/input/`**
   with the **same name**:
   ```
   episode01.mp4
   episode01.srt      (episode01.zh.srt also works)
   episode02.mp4
   episode02.srt
   ```
2. In Termux type:
   ```bash
   khmerdub
   ```
3. Dubbed videos appear in **`Internal storage/KhmerDubber/output/`** as `episode01_KH.mp4`
   (plus `episode01_KH.srt`).

All pairs in `input/` are done one after another. Videos that are already finished are skipped.
If it stops (no internet, phone killed Termux), just run `khmerdub` again. It continues
where it stopped.

**One-tap button (optional):** install the **Termux:Widget** app and add its widget to your home
screen. Tap **Khmer Dub** to run it without typing.

## Settings

Edit `Internal storage/KhmerDubber/config.json`:

| Setting | Default | What it does |
|---|---|---|
| `voice` | `"female"` | `"female"` (Sreymom) or `"male"` (Piseth) |
| `rate` | `"+0%"` | Speaking speed. `"+15%"` = faster, `"-10%"` = slower |
| `pitch` | `"+0Hz"` | Voice pitch, e.g. `"+10Hz"` |
| `max_speed` | `1.6` | Max speed-up so a long line fits its time. Higher = better sync, less natural |
| `original_volume` | `0.25` | Original video sound. `0` = remove completely, `1` = full |
| `duck_original` | `true` | Lower the original sound even more while the Khmer voice speaks |
| `dub_volume` | `1.0` | Khmer voice volume |
| `translate` | `"auto"` | `"auto"` = translate only if not Khmer, `"always"`, `"never"` |
| `source_language` | `"auto"` | Language of your .srt: `"auto"`, `"zh-CN"`, `"en"`, `"th"`, `"ko"`… |
| `embed_subtitles` | `false` | Also put the Khmer subtitles inside the video (can be turned on/off in the player) |
| `skip_songs` | `true` | Don't speak lines with ♪ (song lyrics) |
| `workers` | `16` | Lines voiced at the same time (16 is fastest). Lower it if you get many errors |

You can also change settings for one run:

```bash
khmerdub --voice male --original-volume 0
khmerdub --rate +10% --max-speed 1.8 --embed-subs
khmerdub /sdcard/Download/ep1.mp4 /sdcard/Download/ep1.srt   # one specific video
khmerdub --help
```

## Tips

**Male and female voices in the same video.** Start a subtitle line with `[M]` or `[F]`:
```
12
00:01:02,000 --> 00:01:04,500
[M] 你到底想怎么样？
```
Lines without a tag use the `voice` from settings.

**Fix a bad translation.**
1. Open `output/episode01_KH.srt`, correct the Khmer text (and add `[M]`/`[F]` if you like).
2. Put it in `input/` as `episode01.srt` (replace the old one) together with the video.
3. Run `khmerdub --force`. It sees the text is already Khmer and dubs exactly what you wrote.

**Lines too fast or out of sync.** Khmer is usually longer than Chinese. Try `"rate": "+10%"` so
the voice is a bit faster everywhere, or raise `max_speed`.

**Keep the screen on / don't swipe Termux away** while it works. The script asks Android not to
sleep, but some phones still kill background apps. You can disable battery optimization for Termux.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Permission denied` on `/sdcard` | Run `termux-setup-storage` and tap Allow |
| `edge-tts is not installed` | `pip install edge-tts` |
| `ffmpeg is not installed` | `pkg install ffmpeg` |
| `translation failed` / voice errors | Check internet, wait a minute, run again (finished parts are saved) |
| Video not found / skipped | The .srt must have the same name as the video |
| Video doesn't show in Gallery | Open it from the file manager, or restart the phone to refresh the Gallery |

Works on a computer too (Windows/macOS/Linux with Python 3.8+ and ffmpeg):
`pip install edge-tts`, then `python khmer_dubber.py --dir some/folder`.
