#!/usr/bin/env python3
"""Measure recordings before writing music with them (needs numpy and ffmpeg).

  python3 tools/music/probe.py pitch    samples/resonant/*.ogg   # fundamental: spectral peak and harmonic product
  python3 tools/music/probe.py envelope samples/impacts/*.wav    # length, onset and loudest moment (to land a hit on a beat)
  python3 tools/music/probe.py timeline samples/resonant/guitar_heavy_00002.ogg
                                                                 # every 0.25 s: level, brightness, peak note, top 3 pitch classes
  python3 tools/music/probe.py sections render.wav 1.4 intro:0:4 drop:4:12
                                                                 # RMS per section, bar length in seconds

A single-window pitch is only a guess (the peak can land on an overtone, the product on a sub);
the timeline says whether a recording is one chord or a riff, and where it falls silent.
To put a recording on a note: pitch = (target - measured) semitones; cents count (A2-18c needs +0.18).
"""
import sys, subprocess, numpy as np
NAMES = "C C# D Eb E F F# G G# A Bb B".split()
def load(f, sr=22050):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", f, "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"],
                         capture_output=True).stdout
    return np.frombuffer(raw, np.float32)
def note(f):
    m = 69 + 12 * np.log2(f / 440)
    return f"{NAMES[int(round(m)) % 12]}{int(round(m)) // 12 - 1}{(m - round(m)) * 100:+.0f}c"
def pitch(files, sr=22050):
    for f in files:
        x = load(f, sr); n = len(x)
        seg = x[int(n * .2):int(n * .2) + min(n // 2, sr * 6)]
        if len(seg) < 4096: seg = x
        S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 18)); fr = np.fft.rfftfreq(1 << 18, 1 / sr)
        m = (fr > 50) & (fr < 1200); hps = S.copy()
        for h in (2, 3): hps[:len(hps) // h] *= S[::h][:len(hps) // h]
        res = [f"{lab} {fr[np.argmax(np.where(m, a, 0))]:6.1f}Hz {note(fr[np.argmax(np.where(m, a, 0))])}"
               for a, lab in ((S, "peak"), (hps, "hps"))]
        print(f.split("/")[-1][:40].ljust(40), " | ".join(res))
def envelope(files, sr=22050):
    for f in files:
        x = load(f, sr); r = np.sqrt(np.convolve(x * x, np.ones(441) / 441, "same"))
        print(f.split("/")[-1][:36].ljust(36), f"len {len(x)/sr:5.2f}s  onset {np.argmax(r > 0.1*r.max())/sr:5.2f}s  "
              f"peak {np.argmax(r)/sr:5.2f}s  peakRMS {20*np.log10(r.max()):5.1f}dB")
def timeline(files, sr=22050):
    for f in files:
        x = load(f, sr); hop, W = sr // 4, 8192; print("==", f)
        for i in range(0, len(x) - W // 2, hop):
            seg = np.pad(x[i:i + W], (0, max(0, W - len(x[i:i + W]))))
            S = np.abs(np.fft.rfft(seg * np.hanning(W), 1 << 16)); fr = np.fft.rfftfreq(1 << 16, 1 / sr)
            m = (fr > 60) & (fr < 1000); ch = np.zeros(12)
            np.add.at(ch, np.round(69 + 12 * np.log2(fr[m] / 440)).astype(int) % 12, S[m] ** 2)
            print(f" {i/sr:5.2f}s {20*np.log10(np.sqrt(np.mean(x[i:i+hop]**2))+1e-9):6.1f}dB  "
                  f"centroid {(S*fr).sum()/(S.sum()+1e-9):5.0f}Hz  peak {note(fr[m][np.argmax(S[m])]):8s} "
                  f"chroma {' '.join(NAMES[k] for k in np.argsort(ch)[::-1][:3])}")
def sections(f, barlen, *secs):
    x = load(f, 44100)
    for s in secs:
        name, a, b = s.split(":"); seg = x[int(float(a)*barlen*44100):int(float(b)*barlen*44100)]
        print(f"{name:10s} {20*np.log10(np.sqrt(np.mean(seg**2))+1e-9):7.1f} dB")
    print(f"{'peak':10s} {20*np.log10(np.abs(x).max()):7.1f} dB (mono fold)")
if __name__ == "__main__":
    if len(sys.argv) < 3: sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "sections": sections(args[0], float(args[1]), *args[2:])
    else: {"pitch": pitch, "envelope": envelope, "timeline": timeline}[cmd](args)
