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

| | Android app (`KhmerDubber-v1.0.apk`) | Termux script (`khmer_dubber.py`) |
|---|---|---|
| Install | Tap the APK | Termux + `install.sh` |
| Use | Tap buttons | Type `khmerdub` |
| Long lines | Re-spoken faster by edge-tts (sounds natural) | Sped up with ffmpeg |
| Needs | Android 8.0+ and internet | Termux, Python, ffmpeg and internet |

---

# Android app

## Install
1. Copy `KhmerDubber-v1.0.apk` to the phone (Telegram, Google Drive, USB…) and tap it.
2. If Android asks, allow **"Install unknown apps"** for the app you opened it from.
3. Android may warn that the app is from an unknown developer. Tap **Install anyway** (it's your own app).

## Use
1. **Add files**: pick the video **and** its `.srt` (same name, e.g. `ep01.mp4` + `ep01.srt`).
   Pick many episodes at once if you want. Green ✓ lines show the pairs it found.
2. Choose the voice (**Listen to voice** plays a sample), speed and original-sound level.
3. Tap **Start dubbing**. You can leave the app; progress shows in the notification.
4. Results:
   - Dubbed videos: **Movies/KhmerDubber** (they show up in the Gallery)
   - Khmer subtitles: **Download/KhmerDubber**

**Stop** stops after the current step. **Start** again continues where it stopped
(translation and finished voice lines are kept).

The `[M]`/`[F]` tags and the "fix a bad translation" tip below work in the app too.

## Rebuild the app (on this Mac)
The source is in `android-app/`. To make a new APK after changing the code:
```bash
cd android-app
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
```
**Keep `android-app/khmerdubber.jks` and `keystore.properties` safe.** Android only installs an
update over the old app if it's signed with the same key.

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
| `workers` | `4` | Lines voiced at the same time. Lower it if you get many errors |

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
