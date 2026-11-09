#!/usr/bin/env python3
"""Tritave Signal: an alien piece in the Bohlen-Pierce scale, in 7/8, written as an SfxLab project.

  python3 tools/music/tritave_signal.py > projects/tritave_signal.sfx
  ./sfxlab --render projects/tritave_signal.sfx renders/tritave.ogg

Tracks as the GUI numbers them: 1 kick, 2 ticks + drips, 3 bass, 4 voice, 5 pad + choir, 6 ghost.

Why it sounds alien:
  - Bohlen-Pierce tuning: no octaves. The 3:1 "tritave" is split into 13 equal steps of 146.3 cents;
    the Lambda mode picks 9 of them. Its consonant chords are odd-harmonic (3:5:7 = steps 0 6 10),
    so the pad and bass use odd harmonics only (tones bank odd/even = -1, interval = a tritave)
  - 7/8 grouped 2+2+3, at an eighth of 0.2 s
  - the voice is an arc with ring near 1 (a pulse train of damped sines, FOF synthesis); every
    syllable gets its own formant, so it seems to form vowels, and notes glide into each other
  - the ghost is a partials resynthesis of a vocal with its harmonics stretched (h -> h^1.12)
    and the non-harmonic partials doubled (purity 2): a voice turned into a bell
Built light enough to play live: about 300 clips, no clip with more than 5 granular voices.
Unknown param keys are dropped silently by the loader, so every key is checked against
runtime/sfxlab/runtime/Sfx.java.
"""
import random, math, re, sys, pathlib
_SFX = pathlib.Path(__file__).resolve().parents[2] / "runtime/sfxlab/runtime/Sfx.java"
VALID = {n.replace(" ", "_") for n in re.findall(r'new PSpec\("([^"]+)"', _SFX.read_text())} | {"file", "keyed"}
random.seed(13)
VR = random.Random(31)          # for musical randomness (formants, chatter), apart from the seeds
E = 0.2                         # an eighth, in seconds; times below are in eighths
BAR = 7                         # 7/8: 2 + 2 + 3
out = ["# SfxLab project v2: clip name type track start dur seed key=value...",
       "# 'Tritave Signal' - Bohlen-Pierce (13 steps per 3:1), 7/8, by Claude. tracks (GUI): 1 kick, 2 ticks+drips, 3 bass, 4 voice, 5 pad+choir, 6 ghost",
       "loop 0", "root 110.0000"]
for i, v in enumerate([0.7, 1.0, 0.8, 0.7, 0.75, 0.65]): out.append(f"track {i} {v:.3f} 0")
def clip(name, typ, tr, t0, t1, **kw):
    bad = set(kw) - VALID
    if bad: sys.exit(f"unknown param key(s) on {name}: {sorted(bad)}")
    s = " ".join(f"{k}={round(v,4) if isinstance(v,float) else v}" for k, v in kw.items())
    out.append(f"clip {name} {typ} {tr} {max(0,t0)*E:.4f} {(t1-t0)*E:.4f} {random.getrandbits(63)} {s}")
TONES, NOISE, CHOIR, PARTIALS, ARC = 1, 2, 6, 7, 8
bar = lambda n: n * BAR
TRITAVE = 12 * math.log2(3)     # 19.02 semitones
STEP = TRITAVE / 13             # 1.463 semitones
bp = lambda n: n * STEP         # BP steps -> semitones
LAMBDA = [0, 2, 3, 4, 6, 7, 9, 10, 12]
def lam_below(n, k=2):          # k scale positions under step n (a Lambda "third")
    o, r = divmod(n, 13); i = LAMBDA.index(r) - k
    return LAMBDA[i % 9] + 13 * (o + i // 9)
DUCK = dict(duck=0.45, duck_from=1)
VIB = {"lfo_rate": 4.5, "lfo>pitch": 0.12}

# chords in BP steps (I, vi, III, V of the Lambda mode, voiced to move least) and their roots
CHORDS = [[0, 6, 10], [-4, 0, 6], [0, 3, 9], [4, 7, 13]]
ROOTS = [0, -4, 3, 7]

# ---------------- percussion ----------------
def kick(t, lv=1.0):
    clip("kick", TONES, 0, t, t + 2.5, level=0.8*lv, attack=0.001, release=0.4, pitch=-8, pitch_swp=-15,
         interval=0, timbre=0.0, env_curve=0.35, keyed=0)
def drip(t, step, lv=1.0):   # a resonant band-pass on noise, tuned to a BP pitch: a drop of something
    f = 220 * 2 ** (bp(step) / 12)
    u = math.log(f / 40) / math.log(250)
    clip("drip", NOISE, 1, t, t + 1.5, level=min(1, 1.0*lv), drive=0.35, attack=0.001, release=0.28, cutoff=min(1, u), filter=1,
         resonance=0.88, color=0.0, env_curve=0.7, pan=round(VR.uniform(-0.7, 0.7), 2), reverb=0.45, echo=0.25, keyed=0)
def ticks(t0, t1, misfire, lv, swp=0.0):   # an arc at the sixteenth rate (divide 4); misfire drops ticks
    clip("ticks", ARC, 1, t0, t1, level=lv, attack=0.0, release=0.1, pitch=12 * math.log2(2 / E * 16 / 110),
         pitch_swp=swp, divide=4, burst=1.2, snap=0.9, color=0.25, misfire=misfire, filter=2, cutoff=0.62,
         pan=-0.4, pan_swp=0.8, keyed=0)
GROOVE = {0: 1.0, 4: 0.75}             # kick on the 2+2+3 boundaries
def groove(b0, b1, drips=True, extra=False):
    for b in range(b0, b1):
        for e, lv in GROOVE.items(): kick(bar(b) + e, lv)
        if extra and b % 2 == 1: kick(bar(b) + 6, 0.55)
        if drips:
            ch = CHORDS[((b - b0) // 2) % 4]
            for e in (2, 5, 6) if b % 2 else (2, 5):
                if VR.random() < 0.8: drip(bar(b) + e + (0.5 if e == 6 else 0), VR.choice(ch) + 13, 0.8 if e == 2 else 0.55)

# ---------------- bass: odd harmonics only (a tritave above, not an octave) ----------------
def bass(b0, b1):
    for b in range(b0, b1):
        r = ROOTS[((b - b0) // 2) % 4]
        for e, ln, st in ((0, 3, r), (4, 2, r), (6, 1, r + 6)):
            clip("bass", TONES, 2, bar(b) + e, bar(b) + e + ln, level=0.6 if e == 0 else 0.45, attack=0.005,
                 release=0.12, pitch=-12 + bp(st), interval=TRITAVE, timbre=0.25, cutoff=0.45, fm_ratio=3.0,
                 fm_depth=0.6, **DUCK)

# ---------------- pad: hollow odd-harmonic tones, phased ----------------
def pad(i, t0, t1, lv=0.26, swp=0.0, rel=1.0):
    for j, st in enumerate(CHORDS[i % 4]):
        clip("pad", TONES, 4, t0, t1, level=lv, attack=1.2, release=rel, pitch=12 + bp(st), pitch_swp=swp,
             interval=TRITAVE if j == 0 else 0, timbre=0.0, detune=0.25, shimmer=0.3, bank=0.7, **{"odd/even": -1.0},
             tilt=-0.4, phaser=0.35, phaser_rate=0.12, pan=(-0.5, 0.0, 0.5)[j], reverb=0.5, **DUCK)

# ---------------- voice: the arc forming vowels, gliding ----------------
def voice(t0, t1, st, prev=None, lv=0.5, formant=None, pan=0.1, glide=0.35, swp=0.0, rel=None):
    p = 12 + bp(st); fm = formant if formant is not None else VR.choice([5, 9, 14, 19, 24, 28])
    common = dict(ring=0.88, formant=fm, burst=11, jitter=0.03, snap=0.3, flicker=0.15, echo=0.22, reverb=0.4, pan=pan)
    hold0 = t0
    if prev is not None and prev != st:   # the glide: from the last note to this one
        clip("voice_glide", ARC, 3, t0, t0 + glide, level=lv, attack=0.004, release=0.02, pitch=12 + bp(prev),
             pitch_swp=bp(st) - bp(prev), **common)
        hold0 = t0 + glide * 0.8
    clip("voice", ARC, 3, hold0, t1, level=lv, attack=0.02, release=rel or min(0.3, (t1 - hold0) * E * 0.4),
         pitch=p, pitch_swp=swp, **common, **VIB)
# melody per two-bar chord: (eighth, length, BP step above A3)
MEL = [[(0,3,10),(3,2,9),(5,2,6),(7,4,7),(11,3,6)],
       [(0,2,6),(2,2,4),(4,3,3),(7,3,0),(10,4,2)],
       [(0,4,9),(4,2,10),(6,1,12),(7,4,13),(11,3,12)],
       [(0,2,13),(2,2,12),(4,3,10),(7,2,9),(9,5,7)]]
def melody(b0, harmonise=False):
    prev = None
    for i, phrase in enumerate(MEL):
        for e, ln, st in phrase:
            t = bar(b0 + 2*i) + e
            voice(t, t + ln, st, prev)
            if harmonise: voice(t + 0.08, t + ln, lam_below(st), None, lv=0.32, pan=-0.35)
            prev = st
def chatter(t0, t1):   # the break: syllables in no language, fast, each its own vowel and inflection
    t = t0; prev = None
    while t < t1 - 0.5:
        ln = VR.choice([0.5, 0.5, 1, 1, 1.5, 2])
        st = VR.choice(LAMBDA[3:] + [13])
        voice(t, t + ln * 0.9, st, prev, lv=0.42, glide=0.2, swp=VR.choice([0, 0, -1.5, 1.5, -3]),
              pan=round(VR.uniform(-0.3, 0.3), 2))
        prev = st; t += ln + (VR.choice([0, 0, 0.5, 1]) if VR.random() < 0.4 else 0)

# ---------------- ghost and choir ----------------
def ghost(t0, t1, lv=0.5, swp=0.0):   # a vocal resynthesised with stretched harmonics: a voice made a bell
    clip("ghost", PARTIALS, 5, t0, t1, level=lv, attack=2.5, release=2.5, pitch=-0.65 + 12, pitch_swp=swp,
         file="luminal/luminal__synth_vocal_base_00001.ogg", start=0.15, speed=0.25, sines=1.0, residual=0.2,
         stretch=0.12, purity=2.0, shimmer=0.6, reverb=0.6, pan=0.2, keyed=0)
def murmur(t0, t1, lv=0.4):           # five ungathered voices wandering a tritave: a crowd far off
    clip("murmur", CHOIR, 4, t0, t1, level=lv, attack=1.5, release=1.5, pitch=-2.94,
         file="luminal/luminal_synth_vocal_treble_00004.ogg", start=0.3, speed=0.3, voices=5, gather=0.0,
         drift=0.8, wander=19, scatter=0.6, reverb=0.6, cutoff=0.75, keyed=0)

# ================= the arrangement (bars of 7/8, 1.4 s each) =================
# ARRIVAL (bars 0-3): the ghost, the tonic chord, the ticks waking
ghost(0, bar(5))
pad(0, bar(1), bar(4) + 2, lv=0.22)
ticks(bar(2), bar(4), misfire=0.7, lv=0.25)
for b, e, st in ((1, 3, 10), (2, 1, 6), (2, 5, 13), (3, 2, 9), (3, 5, 19)): drip(bar(b) + e, st + 13, 0.7)
# SIGNAL (bars 4-11): groove, bass, the chord cycle, the voice
groove(4, 12)
bass(4, 12)
ticks(bar(4), bar(12), misfire=0.4, lv=0.55)
for i in range(4): pad(i, bar(4 + 2*i), bar(6 + 2*i) + 1.5)
melody(4)
# INTERFERENCE (bars 12-15): no beat; the crowd, the voice chatters, the chord cycle slows to one per bar
murmur(bar(12), bar(16) + 2)
for i in range(4): pad(i, bar(12 + i), bar(13 + i) + 1.5, lv=0.24)
chatter(bar(12) + 1, bar(16))
for b in range(12, 16): kick(bar(b), 0.6)
ticks(bar(15), bar(16), misfire=0.2, lv=0.3)
# SIGNAL, AGAIN (bars 16-23): the voice in Lambda thirds, the ghost returns underneath
groove(16, 24, extra=True)
bass(16, 24)
ticks(bar(16), bar(24), misfire=0.3, lv=0.6)
for i in range(4): pad(i, bar(16 + 2*i), bar(18 + 2*i) + 1.5)
melody(16, harmonise=True)
ghost(bar(16), bar(24), lv=0.3)
# THE POCKET CLOSES (bar 24): everything falls a tritave; the clock slows to a stop
kick(bar(24))
pad(0, bar(24), bar(24) + 30, lv=0.3, swp=-TRITAVE, rel=8)
voice(bar(24), bar(24) + 30, 10, 7, lv=0.45, formant=9, swp=-TRITAVE, rel=8)
clip("bass", TONES, 2, bar(24), bar(24) + 25, level=0.6, attack=0.005, release=4, pitch=-12, pitch_swp=-6,
     interval=TRITAVE, timbre=0.25, cutoff=0.45)
ticks(bar(24), bar(24) + 25, misfire=0.3, lv=0.3, swp=-30)
ghost(bar(24), bar(24) + 30, lv=0.45, swp=-TRITAVE)
print("\n".join(out))
