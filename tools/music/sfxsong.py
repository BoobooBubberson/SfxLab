"""Tiny helper for writing SfxLab .sfx projects from Python, in beats.

    from sfxsong import Song
    s = Song("Title", bpm=120, seed=1, comment="...")
    s.clip("kick", "tones", 0, beat=0, beats=0.75, level=1, pitch=3, pitch_swp=-22, keyed=0)
    s.write()                  # .sfx to stdout, a summary to stderr

Pitch is semitones from A2 = 110 Hz (see README). Times are beats; the release
fades inside the clip, so give ringing notes their ring in `beats`.

Every key is checked against the PSpec tables in runtime/sfxlab/runtime/Sfx.java, per
clip type (a key another type has is still an error: the loader skips it silently), and a
value outside its slider's range is reported. Outside the repo, set SFX_JAVA or pass
--sfx-java; without either the README's key list is used.
"""
import math, os, random, re, sys

TYPES = dict(cloud=0, tones=1, noise=2, sparkle=3, pluck=4, sample=5, choir=6, partials=7, arc=8)

KNOWN = set("""
level attack release pitch pitch_swp cutoff cutoff_swp resonance filter pan pan_swp echo drive
reverb env_curve lfo_rate lfo>pitch lfo>cut lfo>amp flange phaser phaser_rate ph_stages duck
duck_from keyed file
interval timbre detune shimmer fm_ratio fm_depth bank odd/even tilt stretch
color density ping_decay spread damp sustain pick start loop pitch_mode speed
voices gather gather_swp chord wander drift scatter sines residual purity
ring formant burst jitter misfire flicker snap divide
""".split())
NOT_SLIDERS = {"file", "keyed"}

CHORD = dict(octaves=0, power=1, major=2, unison=3, sus4=4, minor=5, harm7=6, overtones=7,
             maj7=8, penta=9, augment=10, harm11=11, tritone=12)


def _find_sfx_java():
    path = os.environ.get("SFX_JAVA")
    if "--sfx-java" in sys.argv:
        path = sys.argv[sys.argv.index("--sfx-java") + 1]
    if path:
        return path
    here = os.path.dirname(os.path.abspath(__file__))
    guess = os.path.join(here, "..", "..", "runtime", "sfxlab", "runtime", "Sfx.java")
    return guess if os.path.exists(guess) else None


def _specs(path):
    """Per clip type: {key: (min, max)}, read from the PSpec tables in Sfx.java."""
    src = open(path, encoding="utf-8").read()
    consts = {"NCHORD": len(re.findall(r'"[^"]+"', re.search(r"CHORD_NAMES = \{(.*?)\};", src, re.S).group(1))),
              "TRACKS": 6, "PH_MAX": 12}
    def ev(e):
        for k, v in consts.items():
            e = e.replace(k, str(v))
        return float(eval(e, {"__builtins__": {}}))
    def specs(block):
        return {n.replace(" ", "_"): (ev(a), ev(b)) for n, a, b, _ in
                re.findall(r'new PSpec\("([^"]+)",\s*([^,]+),\s*([^,]+),\s*([^)]+)\)', block)}
    common = specs(re.search(r"PSpec\[\] COMMON = \{(.*?)\};", src, re.S).group(1))
    tail = specs(re.search(r"PSpec\[\] TAIL_SPECS = \{(.*?)\};", src, re.S).group(1))
    extras = re.search(r"PSpec\[\]\[\] EXTRAS = \{(.*?)\n    \};", src, re.S).group(1)
    per = [specs(b) for b in extras.split("withLfo(")[1:]]
    if len(per) != len(TYPES):
        raise RuntimeError(f"{path}: found {len(per)} type tables, expected {len(TYPES)}")
    return [{**common, **tail, **e} for e in per]


def _allowed_keys():
    path = _find_sfx_java()
    if not path:
        return None, "README list"
    return _specs(path), os.path.relpath(path)


def cutoff_for_hz(hz):
    """The cutoff slider value u for a frequency: 40 Hz * 250^u."""
    return max(0.0, min(1.0, math.log(hz / 40.0) / math.log(250.0)))


def hz(pitch):
    return 110.0 * 2 ** (pitch / 12.0)


def ticker_pitch(bpm, per_beat=4):
    """Arc pitch whose divide=4 pulse train fires `per_beat` ticks per beat."""
    rate = bpm / 60.0 * per_beat
    return 12 * math.log2(rate * 16 / 110.0)


def _fmt(v):
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        s = f"{v:.4f}".rstrip("0").rstrip(".")
        return s if s not in ("-0", "") else "0"
    return str(v)


class Song:
    def __init__(self, title, bpm, seed, comment="", loop=0, tracks=None, root=110.0):
        self.title, self.bpm, self.comment, self.loop, self.root = title, bpm, comment, loop, root
        self.spb = 60.0 / bpm
        self.R = random.Random(seed)            # seeds + musical randomness
        self.tracks = tracks or [(1.0, 0)] * 6  # (volume, mute)
        self.clips = []
        self.warnings = set()
        self.short_env = {}                     # clip name -> worst loss (dB) from release > length
        self.max_beat = None                    # set for loops: clips are cut to end by here
        self.allowed, self.allowed_src = _allowed_keys()

    def clip(self, name, typ, track, beat, beats, seed=None, **kw):
        if " " in name or not name:
            raise ValueError(f"clip name {name!r}: no spaces")
        if typ not in TYPES:
            raise ValueError(f"unknown clip type {typ!r}")
        if not 0 <= track <= 5:
            raise ValueError(f"track {track} out of 0-5")
        if self.allowed is None:
            bad = [k for k in kw if k not in KNOWN]
        else:
            spec = self.allowed[TYPES[typ]]
            bad = [k for k in kw if k not in spec and k not in NOT_SLIDERS]
            for k, v in kw.items():
                if k in spec and not isinstance(v, str):
                    lo, hi = spec[k]
                    if not lo - 1e-9 <= v <= hi + 1e-9:
                        self.warnings.add(f"{name}.{k}={v} outside its slider {lo:g}..{hi:g}")
        if bad:
            raise ValueError(f"{name} ({typ}): no such key(s) {bad} (checked against {self.allowed_src})")
        if self.max_beat is not None:          # looping projects: keep tails inside the loop
            beats = min(beats, self.max_beat - beat)
        if beats <= 0:
            raise ValueError(f"{name} at beat {beat}: non-positive length")
        # the envelope is min(t / attack, (dur - t) / release) ^ 8^env_curve: a release longer than the
        # clip means it starts already partway down its fade (peak dur/release before the curve)
        dur_s = beats * self.spb
        if kw.get("release") == "full":           # percussive: decay across the whole clip
            kw["release"] = round(max(0.0, dur_s - kw.get("attack", 0.02)), 4)
        rel, att = kw.get("release", 0.25), kw.get("attack", 0.02)
        if att + rel > dur_s * 1.001:
            peak = min(1.0, dur_s / (att + rel)) ** (8 ** kw.get("env_curve", 0.0))
            self.short_env[name] = max(self.short_env.get(name, 0), -20 * math.log10(max(peak, 1e-6)))
        seed = self.R.getrandbits(63) if seed is None else seed
        self.clips.append((beat * self.spb, beats * self.spb, name, TYPES[typ], track, seed, kw))

    def write(self, out=None):
        out = out or sys.stdout
        out.write("# SfxLab project v2: clip name type track start dur seed key=value...\n")
        out.write(f"# '{self.title}' - {self.comment}\n")
        out.write(f"loop {self.loop}\nroot {self.root:.4f}\n")
        for i, (vol, mute) in enumerate(self.tracks):
            out.write(f"track {i} {vol:.3f} {mute}\n")
        for start, dur, name, t, track, seed, kw in sorted(self.clips, key=lambda c: (c[0], c[4])):
            params = " ".join(f"{k}={_fmt(v)}" for k, v in kw.items())
            out.write(f"clip {name} {t} {track} {start:.4f} {dur:.4f} {seed} {params}\n")
        self.summary()

    def summary(self):
        end = max(s + d for s, d, *_ in self.clips)
        per = [0] * 6
        for c in self.clips:
            per[c[4]] += 1
        # peak simultaneous clips (a rough cost for live playback)
        ev = sorted([(s, 1) for s, d, *_ in self.clips] + [(s + d, -1) for s, d, *_ in self.clips])
        cur = peak = 0
        for _, e in ev:
            cur += e
            peak = max(peak, cur)
        sys.stderr.write(f"{self.title}: {len(self.clips)} clips, {end:.1f} s, per track {per}, "
                         f"peak {peak} at once; keys checked against {self.allowed_src}\n")
        for w in sorted(self.warnings):
            sys.stderr.write(f"  warning: {w}\n")
        for n, loss in sorted(self.short_env.items()):
            if loss > 0.5:
                sys.stderr.write(f"  warning: {n}: release (or attack + release) longer than the clip; "
                                 f"it never reaches full level, up to {loss:.0f} dB down\n")
