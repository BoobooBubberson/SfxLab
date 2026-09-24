#!/usr/bin/env python3
"""Sines + residual decomposition of a recording, with partial transposition.

Prototype of spectral-modeling synthesis (Serra & Smith): every stable sinusoid
in the STFT is tracked frame to frame, the tracked peaks are notched out of the
spectrogram to leave a residual (noise, crackle, breath), and an oscillator bank
resynthesizes the partials at any transposition. Residual + shifted partials =
the same recording singing a different note.

    python3 tools/partials.py in.ogg outdir [--keys 0,5,7] [--min-len 8] [--floor 14]

Writes <name>_sines.wav, <name>_residual.wav and <name>_k<N>.wav per key,
plus a one-line summary. Needs numpy, scipy and ffmpeg.
"""
import sys, os, subprocess, argparse
import numpy as np
import scipy.signal as sg

SR = 44100
N = 2048          # window (46 ms): ~21 Hz bins, enough to separate low partials
HOP = 256         # 5.8 ms: fast enough to follow arcs and sweeps
FMIN, FMAX = 40, 12000
MAX_PEAKS = 40

def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True, check=True, stdin=subprocess.DEVNULL).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)

def save(path, x):
    from scipy.io import wavfile
    x = np.clip(x, -1, 1)
    wavfile.write(path, SR, (x * 32767).astype(np.int16))

def peaks_of(mag, floor_db, abs_floor_db=-np.inf):
    """Interpolated spectral peaks of one frame: (bin, freq, amp) sorted loud-first.
    A peak must stand `floor_db` above the frame's median level, so noise
    bumps are mostly ignored before tracking even starts, and above
    `abs_floor_db` (set from the whole file's peak) so quiet tails don't
    sprout tracks out of the noise floor."""
    db = 20 * np.log10(mag + 1e-12)
    ref = max(np.median(db) + floor_db, abs_floor_db)
    lo, hi = int(FMIN * N / SR), int(FMAX * N / SR)
    d = db[lo:hi]
    idx = np.where((d[1:-1] > d[:-2]) & (d[1:-1] >= d[2:]) & (d[1:-1] > ref))[0] + 1 + lo
    out = []
    for k in idx:
        a, b, c = db[k - 1], db[k], db[k + 1]
        den = a - 2 * b + c
        p = 0.5 * (a - c) / den if den != 0 else 0.0     # parabolic interpolation
        out.append((k + p, (k + p) * SR / N, 10 ** ((b - 0.25 * (a - c) * p) / 20)))
    out.sort(key=lambda t: -t[2])
    return out[:MAX_PEAKS]

def track(frames, tol=0.06, max_gap=3, min_len=8):
    """Greedy nearest-frequency continuation. Returns tracks as lists of
    (frame, bin, freq, amp); tracks shorter than min_len frames are dropped."""
    active, done = [], []
    for fi, pk in enumerate(frames):
        used = [False] * len(pk)
        for tr in sorted(active, key=lambda t: -t["pts"][-1][3]):
            f0 = tr["pts"][-1][2]
            best, bd = -1, tol
            for j, (b, f, a) in enumerate(pk):
                if used[j]:
                    continue
                d = abs(f - f0) / f0
                if d < bd:
                    best, bd = j, d
            if best >= 0:
                used[best] = True
                b, f, a = pk[best]
                tr["pts"].append((fi, b, f, a)); tr["gap"] = 0
            else:
                tr["gap"] += 1
        still = []
        for tr in active:
            (done if tr["gap"] > max_gap else still).append(tr)
        active = still
        for j, (b, f, a) in enumerate(pk):
            if not used[j]:
                active.append({"pts": [(fi, b, f, a)], "gap": 0})
    done += active
    return [t["pts"] for t in done if len(t["pts"]) >= min_len]

def resynth(tracks, n_samples, semitones):
    """Oscillator bank: per track, linear freq/amp between frames, phase
    accumulated (no measured phase — it would be wrong after a shift anyway),
    one-hop fades at both ends so partials never click in or out."""
    out = np.zeros(n_samples)
    ratio = 2 ** (semitones / 12)
    for pts in tracks:
        fr = np.array([p[0] for p in pts]); f = np.array([p[2] for p in pts]) * ratio; a = np.array([p[3] for p in pts])
        t = fr * HOP
        t = np.concatenate([[t[0] - HOP], t, [t[-1] + HOP]]); f = np.concatenate([[f[0]], f, [f[-1]]]); a = np.concatenate([[0], a, [0]])
        s0, s1 = max(0, int(t[0])), min(n_samples, int(t[-1]) + 1)
        if s1 <= s0:
            continue
        n = np.arange(s0, s1)
        fi = np.interp(n, t, f); ai = np.interp(n, t, a)
        ph = 2 * np.pi * np.cumsum(fi) / SR
        out[s0:s1] += ai * np.cos(ph)
    return out * (2.0 / N) * 2   # Hann-window magnitude -> linear amplitude

def residual_of(Z, tracks, width=3):
    """Notch every tracked peak out of the STFT (raised-cosine, ±width bins)."""
    Z = Z.copy()
    for pts in tracks:
        for fi, b, f, a in pts:
            k = int(round(b))
            for d in range(-width - 1, width + 2):
                kk = k + d
                if 0 <= kk < Z.shape[0]:
                    w = 0.5 - 0.5 * np.cos(np.pi * min(1.0, abs(d) / (width + 1)))   # 0 at peak -> 1 at edge
                    Z[kk, fi] *= w
    return Z

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("infile"); ap.add_argument("outdir")
    ap.add_argument("--keys", default="0,5,7")
    ap.add_argument("--min-len", type=int, default=8, help="min track length in frames (8 = 46 ms)")
    ap.add_argument("--floor", type=float, default=14, help="dB a peak must rise above the frame median")
    ap.add_argument("--tol", type=float, default=0.06, help="frame-to-frame freq tolerance (fraction)")
    ap.add_argument("--range", type=float, default=60, help="ignore peaks more than this many dB under the file's loudest bin")
    a = ap.parse_args()
    x = load(a.infile)
    name = os.path.splitext(os.path.basename(a.infile))[0]
    os.makedirs(a.outdir, exist_ok=True)
    fq, tt, Z = sg.stft(x, SR, window="hann", nperseg=N, noverlap=N - HOP, boundary="zeros", padded=True)
    Z *= N / 2                         # scipy scales by 1/sum(window); undo -> plain windowed FFT
    gmax = 20 * np.log10(np.abs(Z).max() + 1e-12)
    frames = [peaks_of(np.abs(Z[:, i]), a.floor, gmax - a.range) for i in range(Z.shape[1])]
    tracks = track(frames, a.tol, 3, a.min_len)
    R = residual_of(Z, tracks)
    _, res = sg.istft(R / (N / 2), SR, window="hann", nperseg=N, noverlap=N - HOP, boundary=True)
    res = res[:len(x)]
    sines = resynth(tracks, len(x), 0)
    e_s, e_r = np.sum(sines ** 2), np.sum(res ** 2)
    save(f"{a.outdir}/{name}_sines.wav", sines)
    save(f"{a.outdir}/{name}_residual.wav", res)
    for k in [float(v) for v in a.keys.split(",")]:
        y = res + (sines if k == 0 else resynth(tracks, len(x), k))
        save(f"{a.outdir}/{name}_k{k:g}.wav", y)
    lens = sorted(len(t) for t in tracks)
    print(f"{name}: {len(tracks)} partial tracks (median {np.median(lens) * HOP / SR * 1000:.0f} ms, longest {lens[-1] * HOP / SR:.2f} s), "
          f"sinusoidal energy {100 * e_s / (e_s + e_r):.0f}% / residual {100 * e_r / (e_s + e_r):.0f}%")

if __name__ == "__main__":
    main()
