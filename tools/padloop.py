#!/usr/bin/env python3
"""Turn a struck or bowed recording that fades away into a level, seamless loop (a pad for a bench layer).

    python3 tools/padloop.py in.flac out.ogg [--skip 0.35] [--floor 25] [--fade 1.5] [--window 0.25] [--stereo]

Three steps, none of which touches the pitch or the fast texture:
  1. crop    the sustain: from just after the strike (the steepest rise in level, plus --skip seconds) to where the
             level has fallen --floor dB under the loudest part (so the noise floor is never boosted far);
  2. level   divide by a slow loudness envelope (--window seconds), so the fade-out becomes a steady level while
             everything faster than the window (beating, scrape, shimmer) stays as it was;
  3. loop    the tail is crossfaded into the head (equal power, --fade seconds, at most a third of the crop), so the
             end runs into the start without a seam.
Prints what it kept, the loop length, how level the result is, and the strongest pitches of the pad (as an offset
from the nearest C-rooted note, for the layer's `pitch`). Needs numpy and ffmpeg."""
import subprocess, sys
import numpy as np

SR = 44100
NAMES = "C C# D D# E F F# G G# A A# B".split()

def load(path, stereo):
    ch = 2 if stereo else 1
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", str(ch), "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, ch).astype(np.float64)

def save(path, x):
    args = ["ffmpeg", "-v", "quiet", "-y", "-f", "f32le", "-ar", str(SR), "-ac", str(x.shape[1]), "-i", "-"]
    if path.endswith(".ogg"): args += ["-c:a", "libvorbis", "-q:a", "6"]
    subprocess.run(args + [path], input=x.astype(np.float32).tobytes(), check=True)

def envelope(x, win):
    """RMS over `win` seconds, per sample (a box average of the power, run twice for a smooth edge)."""
    n = max(1, int(win * SR)); p = np.pad((x ** 2).mean(axis=1), n, mode="edge")
    for _ in range(2):                                       # a running mean by cumulative sums
        c = np.cumsum(np.concatenate(([0.0], p)))
        m = (c[n:] - c[:-n]) / n
        p = np.concatenate((np.full(n // 2, m[0]), m, np.full(len(p) - len(m) - n // 2, m[-1])))
    return np.sqrt(np.maximum(p[n:-n], 1e-12))

def pitches(x, count=5):
    n = 32768; m = x.mean(axis=1); acc = np.zeros(n // 2 + 1)
    for i in range(0, len(m) - n, n // 2): acc += np.abs(np.fft.rfft(m[i:i + n] * np.hanning(n))) ** 2
    fr = np.fft.rfftfreq(n, 1 / SR)
    pk = [k for k in range(2, len(acc) - 2) if acc[k] > acc[k - 1] and acc[k] > acc[k + 1] and 30 < fr[k] < 6000]
    pk.sort(key=lambda k: -acc[k]); out = []
    for k in pk[:count]:
        d = (acc[k - 1] - acc[k + 1]) / (2 * (acc[k - 1] - 2 * acc[k] + acc[k + 1])); f = (k + d) * SR / n
        st = 12 * np.log2(f / 65.4064); r = int(round(st))
        out.append((f, NAMES[r % 12] + str(r // 12 + 2), (st - r) * 100, 10 * np.log10(acc[k] / acc[pk[0]])))
    return out

def main():
    a = sys.argv[1:]
    def opt(name, default):
        if name in a: i = a.index(name); v = float(a[i + 1]); del a[i:i + 2]; return v
        return default
    skip, floor, fade, win = opt("--skip", 0.35), opt("--floor", 25), opt("--fade", 1.5), opt("--window", 0.25)
    stereo = "--stereo" in a
    if stereo: a.remove("--stereo")
    if len(a) != 2: sys.exit(__doc__)
    x = load(a[0], stereo)
    # 1. crop: the stretch around the loudest point that stays within `floor` dB of it, starting after the strike
    e = envelope(x, 0.05); edb = 20 * np.log10(e); top = int(e.argmax()); lim = edb[top] - floor
    lo = top
    while lo > 0 and edb[lo - 1] > lim: lo -= 1
    hi = top
    while hi < len(e) - 1 and edb[hi + 1] > lim: hi += 1
    hop = int(0.05 * SR); rise = edb[hop:] - edb[:-hop]            # dB gained over 50 ms: the strike is its maximum
    strike = int(rise[lo:max(lo + 1, hi - hop)].argmax()) + lo + hop
    start = strike + int(skip * SR) if rise[strike - hop] > 10 else lo
    if start == lo and lo < 0.2 * SR: start = lo + int(skip * SR)   # the file opens on its strike: no rise to find, skip it all the same
    start = min(max(start, lo), hi - SR)                           # keep at least a second
    seg = x[start:hi]
    # 2. level: a steady loudness, the fast texture untouched
    env = envelope(seg, win); seg = seg * (np.median(env) / env)[:, None]
    # 3. loop: crossfade the tail into the head
    f = int(min(fade * SR, len(seg) / 3)); t = np.linspace(0, np.pi / 2, f)[:, None]
    out = seg[:len(seg) - f].copy()
    out[:f] = seg[:f] * np.sin(t) + seg[len(seg) - f:] * np.cos(t)
    out *= 10 ** (-3 / 20) / np.abs(out).max()
    save(a[1], out)
    lev = 20 * np.log10(envelope(out, 0.5)[::SR // 4])
    print(f"{a[0]} -> {a[1]}")
    print(f"  kept {start / SR:.2f}-{hi / SR:.2f} s of {len(x) / SR:.1f} s"
          f"{' (strike at %.2f s skipped)' % (strike / SR) if start > lo else ' (no strike found)'}; loop {len(out) / SR:.2f} s, crossfade {f / SR:.2f} s")
    print(f"  level {20 * np.log10(np.sqrt((out ** 2).mean())):.1f} dB rms, within {lev.max() - lev.min():.1f} dB over the loop; "
          f"step across the seam {np.abs(out[0] - out[-1]).max():.4f} (a typical sample-to-sample step is {np.abs(np.diff(out, axis=0)).mean():.4f})")
    print("  pitches: " + "  ".join(f"{f:.0f} Hz {n}{c:+.0f}c ({d:.0f} dB)" for f, n, c, d in pitches(out)))

if __name__ == "__main__":
    main()
