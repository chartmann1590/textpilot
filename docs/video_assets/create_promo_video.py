#!/usr/bin/env python3
"""
TextPilot Promo Video Creator

Generates a polished ~75-second promo video with:
  - Neural TTS voice (Microsoft Edge JennyNeural)
  - xfade transitions (fade, slide, wipe, circle)
  - Synchronized burned-in captions
  - Blurred background fill for portrait screenshots

Requirements:
  pip install edge-tts
  ffmpeg installed and in PATH

Usage (from repo root or docs/video_assets/):
  python docs/video_assets/create_promo_video.py
"""

import asyncio
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR  = Path(__file__).resolve().parent
REPO_ROOT   = SCRIPT_DIR.parent.parent
TMP_DIR     = SCRIPT_DIR / "tmp_promo"
OUTPUT_PATH = REPO_ROOT / "public" / "assets" / "videos" / "promo.mp4"

# ── Settings ───────────────────────────────────────────────────────────────────
VOICE       = "en-US-JennyNeural"
W, H        = 1920, 1080
FPS         = 30
XFADE_DUR   = 0.6
TRANSITIONS = [
    "fade", "slideleft", "wipeleft",
    "slideright", "wipeup", "circleopen",
]


# ── Image resolver ─────────────────────────────────────────────────────────────
def _img(*candidates):
    """Return the first Path that exists, else None."""
    for c in candidates:
        p = REPO_ROOT / c
        if p.exists():
            return p
    return None


# ── Scene definitions ──────────────────────────────────────────────────────────
RAW_SCENES = [
    dict(
        name="intro",
        image=_img("public/assets/feature_graphic.png"),
        duration=6,
        narration="Meet TextPilot — the smarter way to text.",
    ),
    dict(
        name="inbox",
        image=_img("public/assets/screenshots/01_main_inbox.png"),
        duration=9,
        narration=(
            "A clean, fast inbox keeps all your conversations organized. "
            "Text, pictures, group chats — all in one place."
        ),
    ),
    dict(
        name="navigation",
        image=_img(
            "public/assets/screenshots/02_drawer_menu.png",
            "public/assets/screenshots/02_navigation_drawer.png",
        ),
        duration=7,
        narration=(
            "Navigate your messaging life with ease. "
            "Everything you need is always one tap away."
        ),
    ),
    dict(
        name="ai_reply",
        image=_img(
            "screenshots/qksms_ai_settings.png",
            "screenshots/qksms_enabled.png",
            "public/assets/screenshots/11_conversation_view.png",
        ),
        duration=10,
        narration=(
            "TextPilot's AI Smart Reply suggests responses as you type, "
            "powered by your own local AI server. "
            "Your messages never leave your network."
        ),
    ),
    dict(
        name="on_device",
        image=_img(
            "screenshots/qksms_ondevice.png",
            "screenshots/qksms_model_picker.png",
            "public/assets/screenshots/08_settings_main.png",
        ),
        duration=9,
        narration=(
            "Choose from multiple AI models — running entirely on your own hardware. "
            "Fast, private, and always in your control."
        ),
    ),
    dict(
        name="compose",
        image=_img("public/assets/screenshots/10_compose_new.png"),
        duration=8,
        narration=(
            "Composing a message feels effortless. "
            "Smart suggestions, emoji, and your full contact list — always right there."
        ),
    ),
    dict(
        name="conversation",
        image=_img("public/assets/screenshots/11_conversation_view.png"),
        duration=8,
        narration=(
            "Conversations flow naturally. "
            "Threaded messages, inline media, and quick replies keep you in the moment."
        ),
    ),
    dict(
        name="customize",
        image=_img(
            "public/assets/screenshots/08_settings_main.png",
            "public/assets/screenshots/09_settings_scrolled.png",
        ),
        duration=8,
        narration=(
            "Customize every detail — choose your theme, configure notifications, "
            "and make TextPilot feel like it was built just for you."
        ),
    ),
    dict(
        name="tools",
        image=_img(
            "public/assets/screenshots/06_backup.png",
            "public/assets/screenshots/04_scheduled.png",
            "public/assets/screenshots/05_blocking.png",
        ),
        duration=8,
        narration=(
            "Schedule messages, block unwanted contacts, back up your chats, "
            "and stay in control of every conversation."
        ),
    ),
    dict(
        name="outro",
        image=_img("public/assets/feature_graphic.png"),
        duration=7,
        narration="TextPilot. Open source. Privacy first. Download it today.",
    ),
]


# ── Dependency check ───────────────────────────────────────────────────────────
def check_deps():
    ok = True
    try:
        subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        print("ERROR: ffmpeg not found. Install it and add it to PATH.")
        print("       https://ffmpeg.org/download.html")
        ok = False
    try:
        import edge_tts  # noqa: F401
    except ImportError:
        print("ERROR: edge-tts not installed.  Run:  pip install edge-tts")
        ok = False
    return ok


# ── TTS generation ─────────────────────────────────────────────────────────────
async def gen_tts(text: str, voice: str, audio_out: Path, srt_out: Path):
    import edge_tts
    comm = edge_tts.Communicate(text, voice, boundary="WordBoundary")
    sub  = edge_tts.SubMaker()
    with audio_out.open("wb") as f:
        async for chunk in comm.stream():
            if chunk["type"] == "audio":
                f.write(chunk["data"])
            elif chunk["type"] == "WordBoundary":
                sub.feed(chunk)
    with srt_out.open("w", encoding="utf-8") as f:
        f.write(sub.get_srt())


# ── SRT parsing & caption grouping ────────────────────────────────────────────
def _srt_sec(t: str) -> float:
    """Convert SRT/VTT timestamp string (HH:MM:SS,mmm or HH:MM:SS.mmm) to seconds."""
    t = t.strip().replace(",", ".")
    parts = t.split(":")
    if len(parts) == 3:
        return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
    if len(parts) == 2:
        return int(parts[0]) * 60 + float(parts[1])
    return float(parts[0])


def parse_srt(path: Path):
    """Return [(start_sec, end_sec, word), ...] from an SRT file (word-level)."""
    entries = []
    for block in re.split(r"\n{2,}", path.read_text(encoding="utf-8")):
        lines = [l.strip() for l in block.splitlines()]
        arrow = next((l for l in lines if "-->" in l), None)
        if not arrow:
            continue
        left, right = arrow.split("-->", 1)
        word_lines = [l for l in lines if "-->" not in l and l and not l.isdigit()]
        word = " ".join(word_lines).strip()
        if word:
            entries.append((_srt_sec(left), _srt_sec(right), word))
    return entries


def group_captions(entries, max_words=6, max_gap=0.5):
    """Group word-level VTT entries into phrase-sized caption blocks."""
    captions = []
    g_start = g_end = None
    words = []

    def flush():
        if words:
            captions.append((g_start, g_end, " ".join(words)))

    for start, end, word in entries:
        if g_start is None:
            g_start = start
        elif start - g_end > max_gap or len(words) >= max_words:
            flush()
            words = []
            g_start = start
        g_end = end
        words.append(word)
    flush()
    return captions


def sec_to_srt(sec: float) -> str:
    h  = int(sec // 3600)
    m  = int((sec % 3600) // 60)
    s  = int(sec % 60)
    ms = int(round((sec - int(sec)) * 1000)) % 1000
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def build_srt(all_captions, scene_offsets):
    """Combine per-scene captions into a single SRT string."""
    lines = []
    idx = 1
    for captions, offset in zip(all_captions, scene_offsets):
        for s, e, text in captions:
            lines += [
                str(idx),
                f"{sec_to_srt(s + offset)} --> {sec_to_srt(e + offset)}",
                text,
                "",
            ]
            idx += 1
    return "\n".join(lines)


# ── Video clip creation ────────────────────────────────────────────────────────
def create_clip(image_path: Path, duration: float, out_path: Path):
    """
    Render one scene clip at W×H.
    Portrait screenshots get a blurred version of themselves as background.
    Landscape/square screenshots are padded with dark colour.
    """
    # The filter_complex:
    #   1. split input into background and foreground copies
    #   2. bg  → scale to COVER → crop centre → heavy blur
    #   3. fg  → scale to FIT (letter-/pillar-box preserved in overlay)
    #   4. overlay fg centred on bg
    vf = (
        "[0:v]split[bg_in][fg_in];"
        f"[bg_in]scale={W}:{H}:force_original_aspect_ratio=increase,"
        f"crop={W}:{H},boxblur=40:4[bg];"
        f"[fg_in]scale={W}:{H}:force_original_aspect_ratio=decrease[fg];"
        "[bg][fg]overlay=(W-w)/2:(H-h)/2[out]"
    )
    cmd = [
        "ffmpeg", "-y",
        "-loop", "1",
        "-framerate", str(FPS),
        "-i", str(image_path),
        "-t", str(duration),
        "-filter_complex", vf,
        "-map", "[out]",
        "-r", str(FPS),
        "-c:v", "libx264",
        "-preset", "fast",
        "-pix_fmt", "yuv420p",
        str(out_path),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg create_clip failed:\n{result.stderr[-3000:]}")


# ── Audio padding ──────────────────────────────────────────────────────────────
def pad_audio(src: Path, duration: float, dst: Path):
    """Pad (or trim) audio to exactly `duration` seconds."""
    cmd = [
        "ffmpeg", "-y",
        "-i", str(src),
        "-af", f"apad=whole_dur={duration}",
        "-t", str(duration),
        "-c:a", "pcm_s16le",
        str(dst),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg pad_audio failed:\n{result.stderr[-2000:]}")


# ── xfade video merge ──────────────────────────────────────────────────────────
def build_xfade_video(clip_paths, durations, out_path: Path):
    """Concatenate N silent video clips with xfade, applied iteratively pair-by-pair."""
    n = len(clip_paths)
    if n == 1:
        shutil.copy(clip_paths[0], out_path)
        return

    tmp_files = []
    current   = clip_paths[0]
    cur_dur   = float(durations[0])

    for i in range(1, n):
        trans   = TRANSITIONS[(i - 1) % len(TRANSITIONS)]
        offset  = cur_dur - XFADE_DUR
        is_last = (i == n - 1)
        dst     = out_path if is_last else out_path.parent / f"_xf_tmp_{i}.mp4"

        cmd = [
            "ffmpeg", "-y",
            "-i", str(current),
            "-i", str(clip_paths[i]),
            "-filter_complex",
            f"[0:v][1:v]xfade=transition={trans}"
            f":duration={XFADE_DUR}:offset={offset:.3f}[vout]",
            "-map", "[vout]",
            "-c:v", "libx264",
            "-preset", "fast",
            "-pix_fmt", "yuv420p",
            str(dst),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if result.returncode != 0:
            raise RuntimeError(
                f"ffmpeg xfade step {i}/{n-1} failed:\n{result.stderr[-2000:]}"
            )

        # Clean up the previous temp file (but never the original clips)
        if tmp_files:
            tmp_files[-1].unlink(missing_ok=True)

        if not is_last:
            tmp_files.append(dst)

        current = dst
        cur_dur = cur_dur + durations[i] - XFADE_DUR
        print(f"    xfade {i}/{n-1} done", flush=True)


# ── acrossfade audio merge ─────────────────────────────────────────────────────
def build_acrossfade_audio(audio_paths, out_path: Path):
    """Concatenate N audio clips with acrossfade, matching video total duration."""
    n = len(audio_paths)
    if n == 1:
        shutil.copy(audio_paths[0], out_path)
        return

    inputs = []
    for p in audio_paths:
        inputs += ["-i", str(p)]

    fc = []
    prev = "0:a"
    for i in range(1, n):
        lbl = f"af{i}" if i < n - 1 else "aout"
        fc.append(
            f"[{prev}][{i}:a]acrossfade=d={XFADE_DUR}:c1=exp:c2=exp[{lbl}]"
        )
        prev = lbl

    cmd = [
        "ffmpeg", "-y",
        *inputs,
        "-filter_complex", ";".join(fc),
        "-map", "[aout]",
        "-c:a", "pcm_s16le",
        str(out_path),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg acrossfade audio failed:\n{result.stderr[-3000:]}")


# ── Final merge: video + audio + captions ─────────────────────────────────────
def merge_final(video: Path, audio: Path, srt: Path, out: Path):
    """Merge video, audio, and burn-in SRT subtitles into the final MP4."""
    # Caption style: white text, semi-transparent black outline, bottom-centre
    style = (
        "Fontname=Arial,Fontsize=26,Bold=1,"
        "PrimaryColour=&H00FFFFFF,"
        "OutlineColour=&H80000000,"
        "Outline=2,Shadow=1,"
        "Alignment=2,MarginV=40"
    )

    # libass resolves subtitle paths relative to the process cwd.
    # Copy the SRT to the repo root so it's always next to where we run from.
    local_srt = REPO_ROOT / "_promo_caps_tmp.srt"
    shutil.copy(srt, local_srt)

    try:
        vf = f"subtitles=_promo_caps_tmp.srt:force_style='{style}'"
        cmd = [
            "ffmpeg", "-y",
            "-i", str(video.resolve()),
            "-i", str(audio.resolve()),
            "-vf", vf,
            "-c:v", "libx264",
            "-preset", "medium",
            "-crf", "18",
            "-c:a", "aac",
            "-b:a", "192k",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            str(out.resolve()),
        ]
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=600,
            cwd=str(REPO_ROOT),
        )
    finally:
        local_srt.unlink(missing_ok=True)

    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg final merge failed:\n{result.stderr[-3000:]}")


# ── Main ────────────────────────────────────────────────────────────────────────
async def amain():
    if not check_deps():
        sys.exit(1)

    # Resolve scenes; fall back to feature_graphic if an image is missing
    fallback = REPO_ROOT / "public" / "assets" / "feature_graphic.png"
    scenes = []
    for s in RAW_SCENES:
        if s["image"] is None:
            print(f"  [WARN] Scene '{s['name']}': no image found, using feature_graphic")
            s = dict(s, image=fallback)
        scenes.append(s)

    n         = len(scenes)
    durations = [s["duration"] for s in scenes]
    total_dur = sum(durations) - (n - 1) * XFADE_DUR

    print(f"\nTextPilot Promo Video Creator")
    print(f"  Scenes : {n}   |   Voice: {VOICE}")
    print(f"  Output : {OUTPUT_PATH}")
    print(f"  Length : ~{total_dur:.0f}s\n")

    TMP_DIR.mkdir(exist_ok=True)

    # -- 1. TTS ------------------------------------------------------------------
    print("-- 1/5  Generating TTS audio")
    audio_padded = []
    srt_paths    = []

    for i, scene in enumerate(scenes):
        print(f"  [{i+1}/{n}] {scene['name']} ...", end=" ", flush=True)

        raw_mp3 = TMP_DIR / f"s{i:02d}_tts.mp3"
        srt_raw = TMP_DIR / f"s{i:02d}_words.srt"
        padded  = TMP_DIR / f"s{i:02d}_audio.wav"

        await gen_tts(scene["narration"], VOICE, raw_mp3, srt_raw)
        pad_audio(raw_mp3, scene["duration"], padded)

        audio_padded.append(padded)
        srt_paths.append(srt_raw)
        print("done")

    # -- 2. Video clips ----------------------------------------------------------
    print("\n-- 2/5  Creating scene video clips")
    clip_paths = []
    for i, scene in enumerate(scenes):
        print(f"  [{i+1}/{n}] {scene['name']} ...", end=" ", flush=True)
        clip = TMP_DIR / f"s{i:02d}_clip.mp4"
        create_clip(scene["image"], scene["duration"], clip)
        clip_paths.append(clip)
        print("done")

    # -- 3. xfade video ----------------------------------------------------------
    print("\n-- 3/5  Merging clips with xfade transitions ...")
    merged_video = TMP_DIR / "video_merged.mp4"
    build_xfade_video(clip_paths, durations, merged_video)
    print("  done")

    # -- 4. acrossfade audio -----------------------------------------------------
    print("\n-- 4/5  Merging audio with acrossfade ...")
    merged_audio = TMP_DIR / "audio_merged.wav"
    build_acrossfade_audio(audio_padded, merged_audio)
    print("  done")

    # -- 5. Captions + final render ----------------------------------------------
    print("\n-- 5/5  Building captions and final render ...")

    # Compute scene start times in the final (xfade-shortened) video
    scene_offsets = []
    cum = 0.0
    for i, d in enumerate(durations):
        scene_offsets.append(max(0.0, cum - i * XFADE_DUR))
        cum += d

    # Parse word timings from per-scene SRT, group into phrases
    all_captions = []
    for srt_raw in srt_paths:
        entries  = parse_srt(srt_raw)
        captions = group_captions(entries, max_words=6, max_gap=0.5)
        all_captions.append(captions)

    srt_path = TMP_DIR / "captions.srt"
    srt_path.write_text(build_srt(all_captions, scene_offsets), encoding="utf-8")

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    merge_final(merged_video, merged_audio, srt_path, OUTPUT_PATH)

    # Cleanup temp files
    shutil.rmtree(TMP_DIR, ignore_errors=True)

    size_mb = OUTPUT_PATH.stat().st_size / 1024 / 1024
    print(f"\n  [OK] {OUTPUT_PATH}")
    print(f"  [OK] Duration: {total_dur:.0f}s   Size: {size_mb:.1f} MB")
    print("\nDone! Open promo.mp4 in any video player to preview.")


def main():
    asyncio.run(amain())


if __name__ == "__main__":
    main()
