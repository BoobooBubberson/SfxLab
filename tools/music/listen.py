#!/usr/bin/env python3
"""Look at a piece instead of only guessing: render stems, measure them, draw them.

  python3 tools/music/listen.py stems  projects/x.sfx /tmp/x          # renders mix.wav + t1..t6.wav (solo per track)
  python3 tools/music/listen.py report /tmp/x 1.379 intro:0:4 drop:8:16
        # RMS per track and section, low end (<120 Hz) and air (>6 kHz) of the mix, peaks
  python3 tools/music/listen.py draw   /tmp/x 1.379 out.png [bar0 bar1]  # spectrograms of the mix and every stem, bar lines
  python3 tools/music/listen.py chroma /tmp/x/t4.wav 1.379 [bar0 bar1] [--beats N]
        # strongest pitch classes per bar (or per N beats): is the lead on the chord?

A muted track renders silent, so a stem is the project with every other track muted. Empty
tracks are skipped. Renders use -Dsfxlab.noautosave=true.
"""
import os, re, subprocess, sys
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NAMES = "C C# D Eb E F F# G G# A Bb B".split()
SR = 44100


def render(sfx, wav):
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + " -Dsfxlab.noautosave=true"
    r = subprocess.run([os.path.join(ROOT, "sfxlab"), "--render", sfx, wav], env=env, cwd=ROOT,
                       capture_output=True, text=True)
    out = [l for l in (r.stdout + r.stderr).splitlines() if "Picked up" not in l]
    if r.returncode:
        sys.exit("\n".join(out))
    return out[-1] if out else ""


def load(wav, mono=True):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", wav, "-ac", "1" if mono else "2", "-ar", str(SR),
                          "-f", "f32le", "-"], capture_output=True).stdout
    x = np.frombuffer(raw, np.float32)
    return x if mono else x.reshape(-1, 2)


def stems(sfx, outdir):
    os.makedirs(outdir, exist_ok=True)
    lines = open(sfx).read().splitlines()
    used = {int(l.split()[3]) for l in lines if l.startswith("clip ")}
    print("mix:", render(os.path.abspath(sfx), os.path.join(outdir, "mix.wav")))
    for t in sorted(used):
        solo = []
        for l in lines:
            if l.startswith("track "):
                p = l.split()
                p[3] = "0" if int(p[1]) == t else "1"
                l = " ".join(p)
            solo.append(l)
        path = os.path.join(outdir, f"t{t + 1}.sfx")
        open(path, "w").write("\n".join(solo) + "\n")
        print(f"t{t + 1}:", render(path, os.path.join(outdir, f"t{t + 1}.wav")))


def db(x):
    return 20 * np.log10(np.sqrt(np.mean(np.square(x))) + 1e-9) if len(x) else -99


def band(x, lo, hi):
    S = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    S[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(S, len(x))


def wavs(d):
    return [("mix", os.path.join(d, "mix.wav"))] + [
        (f"t{i}", os.path.join(d, f"t{i}.wav")) for i in range(1, 7) if os.path.exists(os.path.join(d, f"t{i}.wav"))]


def report(d, barlen, *secs):
    barlen = float(barlen)
    secs = [(n, float(a), float(b)) for n, a, b in (s.split(":") for s in secs)]
    print("RMS dB      " + "".join(f"{n[:9]:>10s}" for n, _, _ in secs))
    data = {}
    for name, w in wavs(d):
        x = load(w)
        data[name] = x
        row = [db(x[int(a * barlen * SR):int(b * barlen * SR)]) for _, a, b in secs]
        print(f"{name:10s}  " + "".join(f"{v:10.1f}" for v in row))
    x = data["mix"]
    lo = band(x, 20, 120)
    hi = band(x, 6000, 20000)
    print("mix <120Hz  " + "".join(f"{db(lo[int(a*barlen*SR):int(b*barlen*SR)]):10.1f}" for _, a, b in secs))
    print("mix >6kHz   " + "".join(f"{db(hi[int(a*barlen*SR):int(b*barlen*SR)]):10.1f}" for _, a, b in secs))
    st = load(os.path.join(d, "mix.wav"), mono=False)
    print(f"peak stereo {20*np.log10(np.abs(st).max()+1e-9):.1f} dBFS, mono fold {20*np.log10(np.abs(x).max()+1e-9):.1f}; "
          f"length {len(x)/SR:.1f} s; clipped samples {int((np.abs(st) >= 0.999).sum())}")


def spec(x, nfft=4096, hop=1024):
    w = np.hanning(nfft)
    n = max(1, (len(x) - nfft) // hop)
    frames = np.stack([x[i * hop:i * hop + nfft] * w for i in range(n)])
    S = np.abs(np.fft.rfft(frames, axis=1)).T
    return 20 * np.log10(S + 1e-7)


def draw(d, barlen, out, bar0=None, bar1=None):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    barlen = float(barlen)
    ws = wavs(d)
    fig, axes = plt.subplots(len(ws), 1, figsize=(16, 2.1 * len(ws)), sharex=True)
    f = np.fft.rfftfreq(4096, 1 / SR)
    keep = (f > 30) & (f < 12000)
    for ax, (name, w) in zip(np.atleast_1d(axes), ws):
        x = load(w)
        if bar0 is not None:
            x = x[int(float(bar0) * barlen * SR):int(float(bar1) * barlen * SR)]
        S = spec(x)[keep]
        t = np.arange(S.shape[1]) * 1024 / SR
        ax.pcolormesh(t, f[keep], S, vmin=S.max() - 80, vmax=S.max(), shading="auto", cmap="magma")
        ax.set_yscale("log"); ax.set_ylim(30, 12000)
        ax.set_ylabel(f"{name}\n{db(x):.0f} dB")
        b0 = float(bar0) if bar0 is not None else 0
        for b in np.arange(0, len(x) / SR / barlen + 1):
            ax.axvline(b * barlen, color="cyan" if (b + b0) % 4 == 0 else "white", lw=0.5, alpha=0.6)
    plt.xlabel("seconds" + (f" from bar {bar0}" if bar0 is not None else ""))
    plt.tight_layout(); plt.savefig(out, dpi=70)
    print("wrote", out)


def chroma(w, barlen, bar0=0, bar1=None, beats=None):
    barlen = float(barlen)
    x = load(w)
    win = barlen if beats is None else barlen / 4 * float(beats)
    bar1 = float(bar1) if bar1 is not None else len(x) / SR / barlen
    t = float(bar0) * barlen
    while t < bar1 * barlen - 1e-6:
        seg = x[int(t * SR):int((t + win) * SR)]
        if len(seg) < 2048:
            break
        S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 17)) ** 2
        fr = np.fft.rfftfreq(1 << 17, 1 / SR)
        m = (fr > 55) & (fr < 2000)
        ch = np.zeros(12)
        np.add.at(ch, np.round(69 + 12 * np.log2(fr[m] / 440)).astype(int) % 12, S[m])
        ch /= ch.max() + 1e-12
        top = [NAMES[k] + f"{ch[k]:.1f}"[1:] for k in np.argsort(ch)[::-1][:4]]
        print(f" bar {t/barlen:6.2f}  {db(seg):6.1f} dB  " + "  ".join(top))
        t += win


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "stems": stems(*a)
    elif cmd == "report": report(*a)
    elif cmd == "draw": draw(*a)
    elif cmd == "chroma":
        beats = None
        if "--beats" in a:
            i = a.index("--beats"); beats = a[i + 1]; a = a[:i] + a[i + 2:]
        chroma(*a, beats=beats)
    else: sys.exit(__doc__)
