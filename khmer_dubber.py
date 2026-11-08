#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Khmer Drama Dubber
==================
Put a video and its .srt subtitle file in the "input" folder, run this
script, and get the video back dubbed in Khmer with edge-tts voices.

  1. read the .srt (any language, any common encoding)
  2. translate every line to Khmer (skipped if the .srt is already Khmer)
  3. speak each line with edge-tts (km-KH-SreymomNeural / km-KH-PisethNeural)
  4. speed up lines that are too long so they fit their subtitle time
  5. mix the Khmer voice into the video (original audio kept quietly in
     the background and ducked while the Khmer voice speaks)

Made for Android (Termux), but works anywhere with Python 3.8+ and ffmpeg.

Usage:
  python khmer_dubber.py                      # dub everything in input/
  python khmer_dubber.py video.mp4 subs.srt   # dub one video
  python khmer_dubber.py --help               # all options
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import wave
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from itertools import islice
from pathlib import Path

try:
    import edge_tts
except ImportError:  # reported nicely in main()
    edge_tts = None

VERSION = "1.0"
SR = 24000  # edge-tts native sample rate (mono)
VOICES = {"female": "km-KH-SreymomNeural", "male": "km-KH-PisethNeural"}
VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".flv", ".ts",
              ".m4v", ".3gp", ".wmv", ".mpg", ".mpeg"}

DEFAULT_CONFIG = {
    "voice": "female",          # "female", "male" or any edge-tts voice name
    "rate": "+0%",              # base speaking speed, e.g. "+10%" or "-5%"
    "pitch": "+0Hz",            # voice pitch, e.g. "+5Hz"
    "max_speed": 1.6,           # max speed-up so a line fits its time slot
    "original_volume": 0.25,    # original video audio: 0 = remove, 1 = full
    "duck_original": True,      # lower original audio more while Khmer speaks
    "dub_volume": 1.0,          # Khmer voice volume
    "translate": "auto",        # "auto" (only if not Khmer), "always", "never"
    "source_language": "auto",  # language of the .srt ("auto", "zh-CN", "en"…)
    "embed_subtitles": False,   # add the Khmer .srt as a subtitle track
    "skip_songs": True,         # don't speak lines with ♪ (song lyrics)
    "workers": 4,               # parallel edge-tts downloads
    "keep_work_files": False,   # keep the temp folder after success
}

FFMPEG = shutil.which("ffmpeg")
FFPROBE = shutil.which("ffprobe")


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------
def log(msg: str = "") -> None:
    print(msg, flush=True)


def die(msg: str) -> None:
    log(f"\n[ERROR] {msg}")
    sys.exit(1)


def progress(label: str, done: int, total: int) -> None:
    width = 24
    filled = int(width * done / total) if total else width
    bar = "#" * filled + "." * (width - filled)
    sys.stdout.write(f"\r      {label} [{bar}] {done}/{total}")
    if done >= total:
        sys.stdout.write("\n")
    sys.stdout.flush()


def fmt_dur(ms: float) -> str:
    s = int(ms // 1000)
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60}:{s % 60:02d}"


def natural_key(p: Path):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", p.name)]


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def save_json(path: Path, data) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


# --------------------------------------------------------------------------
# SRT
# --------------------------------------------------------------------------
@dataclass
class Cue:
    start: int          # ms
    end: int            # ms
    text: str           # original text, cleaned, single line
    voice: str = ""     # "" = default voice, else "male" / "female"
    km: str = ""        # Khmer text that will be spoken / written


TIME_RE = re.compile(
    r"(\d+):(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?\s*-->\s*"
    r"(\d+):(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?")
TAG_RE = re.compile(r"<[^>]*>|\{[^}]*\}")
SPEAKER_RE = re.compile(r"^\s*\[(m|f|male|female)\]\s*", re.I)


def read_text_any(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16")
    for enc in ("utf-8-sig", "gb18030", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8", errors="replace")


def _ms(h, m, s, frac) -> int:
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int((frac or "0").ljust(3, "0")[:3])


def parse_srt(path: Path) -> list[Cue]:
    lines = read_text_any(path).replace("\r\n", "\n").replace("\r", "\n").split("\n")
    entries: list[tuple[int, int, list[str]]] = []
    for line in lines:
        m = TIME_RE.search(line)
        if m:
            if entries:  # drop the next cue's index number from the previous body
                body = entries[-1][2]
                while body and not body[-1].strip():
                    body.pop()
                if body and body[-1].strip().isdigit():
                    body.pop()
            entries.append((_ms(*m.group(1, 2, 3, 4)), _ms(*m.group(5, 6, 7, 8)), []))
        elif entries:
            entries[-1][2].append(line)

    cues = []
    for start, end, body in entries:
        parts = [TAG_RE.sub("", l).strip() for l in body]
        parts = [p for p in parts if p]
        if not parts:
            continue
        voice = ""
        m = SPEAKER_RE.match(parts[0])
        if m:
            voice = "male" if m.group(1).lower().startswith("m") else "female"
            parts[0] = parts[0][m.end():]
        text = re.sub(r"\s+", " ", " ".join(parts)).strip()
        if text:
            cues.append(Cue(start, max(end, start + 1), text, voice))
    cues.sort(key=lambda c: c.start)
    return cues


def srt_time(ms: int) -> str:
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def write_srt(cues: list[Cue], path: Path, with_tags: bool) -> None:
    out = []
    for n, c in enumerate(cues, 1):
        tag = {"male": "[M] ", "female": "[F] "}.get(c.voice, "") if with_tags else ""
        out += [str(n), f"{srt_time(c.start)} --> {srt_time(c.end)}", tag + (c.km or c.text), ""]
    path.write_text("\n".join(out), encoding="utf-8")


def khmer_ratio(texts: list[str]) -> float:
    km = letters = 0
    for t in texts:
        for ch in t:
            if "ក" <= ch <= "៿" or "᧠" <= ch <= "᧿":
                km += 1
                letters += 1
            elif ch.isalpha():
                letters += 1
    return km / letters if letters else 0.0


# --------------------------------------------------------------------------
# Translation (free Google Translate endpoint, no API key)
# --------------------------------------------------------------------------
GT_URL = "https://translate.googleapis.com/translate_a/single"


def google_translate(text: str, src: str = "auto", dest: str = "km") -> str:
    query = urllib.parse.urlencode({"client": "gtx", "sl": src, "tl": dest, "dt": "t"})
    body = urllib.parse.urlencode({"q": text}).encode()
    req = urllib.request.Request(
        f"{GT_URL}?{query}", data=body,
        headers={"User-Agent": "Mozilla/5.0",
                 "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"})
    last = None
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.loads(r.read().decode("utf-8"))
            return "".join(seg[0] for seg in (data[0] or []) if seg and seg[0])
        except Exception as e:  # network hiccup / rate limit -> wait and retry
            last = e
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"translation failed ({last}). Check your internet connection.")


def translate_all(cues: list[Cue], src: str, cache_file: Path) -> None:
    cache: dict = load_json(cache_file, {})
    todo, seen = [], set()
    for c in cues:
        if c.text not in cache and c.text not in seen:
            todo.append(c.text)
            seen.add(c.text)

    # send many lines per request, one per line; keeps requests few and fast
    chunks, cur, size = [], [], 0
    for t in todo:
        if cur and size + len(t) + 1 > 1500:
            chunks.append(cur)
            cur, size = [], 0
        cur.append(t)
        size += len(t) + 1
    if cur:
        chunks.append(cur)

    done = len(cues) - len(todo)
    total = len(cues)
    if todo:
        progress("translate", done, total)
    for chunk in chunks:
        out = google_translate("\n".join(chunk), src).strip("\n").split("\n")
        if len(out) != len(chunk):  # lines got merged -> translate one by one
            out = [google_translate(t, src) for t in chunk]
        cache.update({k: v.strip() for k, v in zip(chunk, out)})
        save_json(cache_file, cache)
        done += len(chunk)
        progress("translate", done, total)
        time.sleep(0.3)

    for c in cues:
        c.km = cache.get(c.text) or c.text


# --------------------------------------------------------------------------
# Text to speech (edge-tts)
# --------------------------------------------------------------------------
def voice_name(v: str) -> str:
    return VOICES.get(v.lower(), v) if v else VOICES["female"]


def norm_rate(v: str, unit: str) -> str:
    v = str(v).strip().replace(" ", "")
    if not v.endswith(unit):
        v += unit
    return v if v[0] in "+-" else "+" + v


def is_speakable(text: str) -> bool:
    return any(ch.isalnum() or "ក" <= ch <= "៿" for ch in text)


def run_tts(cues: list[Cue], cfg: dict, tts_dir: Path) -> dict[int, Path]:
    rate = norm_rate(cfg["rate"], "%")
    pitch = norm_rate(cfg["pitch"], "Hz")
    clips: dict[int, Path] = {}
    jobs: dict[Path, tuple[str, str]] = {}
    for i, c in enumerate(cues):
        if cfg["skip_songs"] and ("♪" in c.text or "♫" in c.text):
            continue
        if not is_speakable(c.km):
            continue
        voice = voice_name(c.voice or cfg["voice"])
        key = hashlib.sha1(f"{voice}|{rate}|{pitch}|{c.km}".encode()).hexdigest()[:20]
        path = tts_dir / f"{key}.mp3"
        clips[i] = path
        if not (path.exists() and path.stat().st_size > 0):
            jobs[path] = (c.km, voice)

    total = len(clips)
    done = [total - len(jobs)]
    failed: list[str] = []
    if not jobs:
        progress("voice", total, total)
        return clips

    async def one(sem, path: Path, text: str, voice: str):
        async with sem:
            err = None
            for attempt in range(4):
                try:
                    tmp = path.with_name(path.name + ".part")
                    await edge_tts.Communicate(text, voice, rate=rate, pitch=pitch).save(str(tmp))
                    if tmp.exists() and tmp.stat().st_size > 0:
                        tmp.replace(path)
                        err = None
                        break
                    err = "empty audio"
                except Exception as e:
                    err = e
                await asyncio.sleep(1.5 * (attempt + 1))
            if err is not None:
                failed.append(f"{text[:40]} ({err})")
            done[0] += 1
            progress("voice", done[0], total)

    async def run_all():
        sem = asyncio.Semaphore(max(1, int(cfg["workers"])))
        await asyncio.gather(*(one(sem, p, t, v) for p, (t, v) in jobs.items()))

    progress("voice", done[0], total)
    asyncio.run(run_all())

    if failed:
        log(f"      ! {len(failed)} line(s) could not be spoken, e.g. {failed[0]}")
        if len(failed) > max(3, total // 2):
            raise RuntimeError("too many voice lines failed. Check your internet and run again "
                               "(finished lines are cached).")
    return {i: p for i, p in clips.items() if p.exists()}


# --------------------------------------------------------------------------
# Timing: build one Khmer voice track that follows the subtitle times
# --------------------------------------------------------------------------
TRIM = ("silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.04,"
        "areverse,silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.08,areverse")


def atempo_chain(t: float) -> list[str]:
    parts = []
    while t > 2.0:
        parts.append("atempo=2.0")
        t /= 2.0
    parts.append(f"atempo={t:.4f}")
    return parts


def decode_pcm(path: Path, tempo: float = 1.0) -> bytes:
    af = [TRIM] + (atempo_chain(tempo) if tempo > 1.005 else [])
    cmd = [FFMPEG, "-v", "error", "-i", str(path), "-af", ",".join(af),
           "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"]
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg could not read {path.name}: {r.stderr.decode(errors='replace')[-300:]}")
    return r.stdout


def ordered_parallel(fn, items, workers: int = 3, ahead: int = 12):
    """Like map(fn, items) but runs a few at once; keeps results in order
    and only a handful in memory."""
    it = iter(items)
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = deque(ex.submit(fn, x) for x in islice(it, ahead))
        while futs:
            res = futs.popleft().result()
            for x in islice(it, 1):
                futs.append(ex.submit(fn, x))
            yield res


def build_track(cues: list[Cue], clips: dict[int, Path], total_ms: int,
                wav_path: Path, max_speed: float) -> dict:
    def smp(ms: float) -> int:
        return int(ms * SR / 1000)

    silence = bytes(SR * 2)  # one second

    def write_silence(w, n: int) -> None:
        while n > 0:
            k = min(n, SR)
            w.writeframes(silence[:k * 2])
            n -= k

    stats = {"lines": 0, "sped": 0, "late": 0, "max_tempo": 1.0}
    order = [i for i in range(len(cues))
             if i in clips and not (total_ms and cues[i].start >= total_ms)]
    decoded = ordered_parallel(lambda i: decode_pcm(clips[i]), order)
    with wave.open(str(wav_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        cursor = 0
        for n, (i, pcm) in enumerate(zip(order, decoded)):
            c = cues[i]
            if n + 1 < len(order):
                slot_end_ms = max(c.end, cues[order[n + 1]].start)
            else:
                slot_end_ms = max(c.end, total_ms or c.end + 2000)
            place = max(smp(c.start), cursor)   # never talk over the previous line
            slot_end = smp(slot_end_ms)

            length = len(pcm) // 2
            avail = slot_end - place
            if length > avail:
                tempo = min(max_speed, length / max(avail, 1))
                if tempo > 1.01:
                    pcm = decode_pcm(clips[i], tempo)
                    stats["sped"] += 1
                    stats["max_tempo"] = max(stats["max_tempo"], tempo)
                if place + len(pcm) // 2 > slot_end + smp(150):
                    stats["late"] += 1

            write_silence(w, place - cursor)
            w.writeframes(pcm)
            cursor = place + len(pcm) // 2
            stats["lines"] += 1
            progress("sync", n + 1, len(order))
        if total_ms and cursor < smp(total_ms):
            write_silence(w, smp(total_ms) - cursor)
    if not order:
        progress("sync", 0, 0)
    return stats


# --------------------------------------------------------------------------
# Video
# --------------------------------------------------------------------------
def probe(video: Path) -> dict:
    r = subprocess.run([FFPROBE, "-v", "error", "-show_entries",
                        "format=duration:stream=codec_type", "-of", "json", str(video)],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        raise RuntimeError(f"cannot read video: {r.stderr.decode(errors='replace').strip()[-300:]}")
    data = json.loads(r.stdout.decode() or "{}")
    types = [s.get("codec_type") for s in data.get("streams", [])]
    return {"duration_ms": int(float(data.get("format", {}).get("duration") or 0) * 1000),
            "has_audio": "audio" in types, "has_video": "video" in types}


def mux(video: Path, wav: Path, out: Path, has_audio: bool, cfg: dict,
        subs: Path | None) -> bool:
    ext = out.suffix.lower()
    cmd = [FFMPEG, "-y", "-hide_banner", "-v", "error", "-stats", "-i", str(video), "-i", str(wav)]
    if subs:
        cmd += ["-i", str(subs)]
    fmt = "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo"
    orig = float(cfg["original_volume"])
    # pan copies mono to both sides at full level (plain upmix would be -3 dB)
    dub = f"[1:a]pan=stereo|c0=c0|c1=c0,{fmt},volume={float(cfg['dub_volume'])}"
    limiter = "alimiter=limit=0.97:level=disabled"
    if has_audio and orig > 0:
        if cfg["duck_original"]:
            fc = (f"[0:a]{fmt},volume={orig}[bg];{dub},asplit=2[d1][d2];"
                  f"[bg][d1]sidechaincompress=threshold=0.015:ratio=12:attack=15:release=400[duck];"
                  f"[duck][d2]amix=inputs=2:duration=first:normalize=0,{limiter}[a]")
        else:
            fc = (f"[0:a]{fmt},volume={orig}[bg];{dub}[d];"
                  f"[bg][d]amix=inputs=2:duration=first:normalize=0,{limiter}[a]")
    else:
        fc = f"{dub}[a]"
    cmd += ["-filter_complex", fc, "-map", "0:v:0", "-map", "[a]"]
    if subs:
        cmd += ["-map", "2:0", "-c:s", "mov_text" if ext in (".mp4", ".m4v", ".mov") else "srt",
                "-metadata:s:s:0", "language=khm"]
    cmd += ["-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest"]
    if ext in (".mp4", ".m4v", ".mov"):
        cmd += ["-movflags", "+faststart"]
    cmd.append(str(out))
    ok = subprocess.run(cmd).returncode == 0
    sys.stdout.write("\n")
    if not ok and out.exists():
        out.unlink()
    return ok


# --------------------------------------------------------------------------
# One video, start to finish
# --------------------------------------------------------------------------
def process(video: Path, srt: Path, out_dir: Path, cfg: dict, force: bool) -> bool:
    log(f"\n=== {video.name}  +  {srt.name} ===")
    out_dir.mkdir(parents=True, exist_ok=True)
    out_video = out_dir / f"{video.stem}_KH.mp4"
    out_srt = out_dir / f"{video.stem}_KH.srt"
    if not force:
        for done in (out_video, out_video.with_suffix(".mkv")):
            if done.exists():
                log(f"  already done -> {done.name}  (use --force to make it again)")
                return True

    work = out_dir / ".work" / re.sub(r"[^\w.-]+", "_", video.stem)
    tts_dir = work / "tts"
    tts_dir.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    try:
        info = probe(video)
        if not info["has_video"]:
            raise RuntimeError("this file has no video stream")
        cues = parse_srt(srt)
        if not cues:
            raise RuntimeError("no subtitle lines found in the .srt file")
        log(f"  {len(cues)} subtitle lines, video length {fmt_dur(info['duration_ms'])}")

        # 1. translate
        mode = str(cfg["translate"]).lower()
        if mode == "never" or (mode == "auto" and khmer_ratio([c.text for c in cues]) > 0.5):
            log("  [1/4] subtitles are already Khmer, no translation needed")
            for c in cues:
                c.km = c.text
        else:
            log("  [1/4] translating subtitles to Khmer")
            translate_all(cues, cfg["source_language"], work / "translation.json")
        write_srt(cues, out_srt, with_tags=True)

        # 2. voice
        log(f"  [2/4] making Khmer voice ({voice_name(cfg['voice'])})")
        clips = run_tts(cues, cfg, tts_dir)
        if not clips:
            raise RuntimeError("no lines could be spoken")

        # 3. timing
        log("  [3/4] fitting voice to subtitle timing")
        wav = work / "dub.wav"
        st = build_track(cues, clips, info["duration_ms"], wav, float(cfg["max_speed"]))
        msg = f"      {st['lines']} lines placed, {st['sped']} sped up"
        if st["sped"]:
            msg += f" (max {st['max_tempo']:.2f}x)"
        if st["late"]:
            msg += f", {st['late']} still too long (started a bit late)"
        log(msg)

        # 4. video
        log("  [4/4] putting the Khmer audio into the video")
        subs = None
        if cfg["embed_subtitles"]:
            subs = work / "subs_clean.srt"
            write_srt(cues, subs, with_tags=False)
        ok = mux(video, wav, out_video, info["has_audio"], cfg, subs)
        if not ok:
            log("      mp4 failed for this video, trying mkv ...")
            out_video = out_video.with_suffix(".mkv")
            ok = mux(video, wav, out_video, info["has_audio"], cfg, subs)
        if not ok:
            raise RuntimeError("ffmpeg could not create the output video")
    except KeyboardInterrupt:
        raise
    except Exception as e:
        log(f"\n  [FAILED] {e}")
        log(f"  Finished steps are saved; run again to continue.")
        return False

    if not cfg["keep_work_files"]:
        shutil.rmtree(work, ignore_errors=True)
        try:
            (out_dir / ".work").rmdir()
        except OSError:
            pass
    log(f"  DONE in {fmt_dur((time.time() - t0) * 1000)}")
    log(f"     video:     {out_video}")
    log(f"     subtitles: {out_srt}")
    return True


# --------------------------------------------------------------------------
# Finding work, config, main
# --------------------------------------------------------------------------
def find_pairs(folder: Path) -> tuple[list[tuple[Path, Path]], list[Path]]:
    files = [p for p in folder.iterdir() if p.is_file() and not p.name.startswith(".")]
    videos = sorted((p for p in files if p.suffix.lower() in VIDEO_EXTS), key=natural_key)
    srts = sorted((p for p in files if p.suffix.lower() == ".srt"), key=natural_key)
    pairs, lonely, used = [], [], set()
    for v in videos:
        match = next((s for s in srts if s not in used and s.stem == v.stem), None)
        if match is None:
            match = next((s for s in srts if s not in used and
                          (s.stem.startswith(v.stem + ".") or s.stem.startswith(v.stem + "_"))), None)
        if match:
            used.add(match)
            pairs.append((v, match))
        else:
            lonely.append(v)
    left = [s for s in srts if s not in used]
    if len(lonely) == 1 and len(left) == 1:  # one video + one srt with different names
        pairs.append((lonely[0], left[0]))
        lonely = []
    return pairs, lonely


def find_srt_for(video: Path) -> Path | None:
    same = video.with_suffix(".srt")
    if same.exists():
        return same
    cands = sorted(video.parent.glob(f"{glob_escape(video.stem)}*.srt"))
    return cands[0] if cands else None


def glob_escape(s: str) -> str:
    return re.sub(r"([*?\[])", r"[\1]", s)


def default_base() -> Path:
    for p in ("/sdcard", "/storage/emulated/0"):
        if os.path.isdir(p) and os.access(p, os.W_OK):
            return Path(p) / "KhmerDubber"
    return Path(__file__).resolve().parent


def load_config(path: Path) -> dict:
    cfg = dict(DEFAULT_CONFIG)
    if path.exists():
        user = load_json(path, None)
        if user is None:
            log(f"[warning] {path} is not valid JSON, using default settings")
        else:
            cfg.update({k: v for k, v in user.items() if k in DEFAULT_CONFIG})
    else:
        save_json(path, DEFAULT_CONFIG)
    return cfg


def termux(cmd: str) -> None:
    if shutil.which(cmd):
        subprocess.run([cmd], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Dub videos in Khmer from .srt subtitles using edge-tts.",
        epilog="Without video/srt arguments, every video+srt pair in the input folder is dubbed.")
    ap.add_argument("video", nargs="?", help="video file (optional)")
    ap.add_argument("srt", nargs="?", help=".srt file (optional, found automatically)")
    ap.add_argument("--dir", help="work folder with input/ and output/ (default: /sdcard/KhmerDubber)")
    ap.add_argument("--voice", help="female, male, or an edge-tts voice name")
    ap.add_argument("--rate", help='speaking speed, e.g. "+10%%"')
    ap.add_argument("--max-speed", type=float, help="max speed-up to fit a line (default 1.6)")
    ap.add_argument("--original-volume", type=float, help="original audio volume 0..1 (0 = remove)")
    ap.add_argument("--no-duck", action="store_true", help="don't lower original audio under the voice")
    ap.add_argument("--embed-subs", action="store_true", help="add Khmer subtitles track to the video")
    ap.add_argument("--no-translate", action="store_true", help="speak the .srt text as it is")
    ap.add_argument("--force", action="store_true", help="redo videos that are already done")
    ap.add_argument("--keep", action="store_true", help="keep temporary work files")
    args = ap.parse_args()

    log(f"Khmer Drama Dubber v{VERSION}")
    if edge_tts is None:
        die("edge-tts is not installed. Run:  pip install edge-tts")
    if not FFMPEG or not FFPROBE:
        die("ffmpeg is not installed. On Termux run:  pkg install ffmpeg")

    base = Path(args.dir).expanduser() if args.dir else default_base()
    base.mkdir(parents=True, exist_ok=True)
    cfg = load_config(base / "config.json")
    if args.voice:
        cfg["voice"] = args.voice
    if args.rate:
        cfg["rate"] = args.rate
    if args.max_speed:
        cfg["max_speed"] = args.max_speed
    if args.original_volume is not None:
        cfg["original_volume"] = args.original_volume
    if args.no_duck:
        cfg["duck_original"] = False
    if args.embed_subs:
        cfg["embed_subtitles"] = True
    if args.no_translate:
        cfg["translate"] = "never"
    if args.keep:
        cfg["keep_work_files"] = True

    if args.video:
        video = Path(args.video).expanduser()
        if not video.exists():
            die(f"video not found: {video}")
        srt = Path(args.srt).expanduser() if args.srt else find_srt_for(video)
        if not srt or not srt.exists():
            die(f"no .srt found for {video.name}. Give it as the second argument.")
        pairs, out_dir = [(video, srt)], video.parent
    else:
        in_dir, out_dir = base / "input", base / "output"
        in_dir.mkdir(exist_ok=True)
        out_dir.mkdir(exist_ok=True)
        pairs, lonely = find_pairs(in_dir)
        for v in lonely:
            log(f"[skip] {v.name}: no .srt with the same name")
        if not pairs:
            log(f"\nNothing to do. Put your video + .srt (same name) here:\n  {in_dir}")
            log(f"Then run this again. Dubbed videos will appear in:\n  {out_dir}")
            log(f"Settings: {base / 'config.json'}")
            return
        log(f"Found {len(pairs)} video(s) to dub. Output: {out_dir}")

    termux("termux-wake-lock")  # keep Android from sleeping mid-job
    results = []
    try:
        for v, s in pairs:
            results.append(process(v, s, out_dir, cfg, args.force))
    except KeyboardInterrupt:
        log("\n\nStopped. Run again to continue where it left off.")
        sys.exit(130)
    finally:
        termux("termux-wake-unlock")

    ok = sum(results)
    log(f"\nFinished: {ok}/{len(results)} video(s) dubbed.")
    if ok < len(results):
        sys.exit(1)


if __name__ == "__main__":
    main()
