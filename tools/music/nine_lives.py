#!/usr/bin/env python3
"""'Nine Lives' (The Coil Forgets, part III) - D minor, 112 BPM. Nine variations on the coil's theme.

The lament ground (D C# C B Bb A in the bass, one chord a bar, eight bars a turn) runs under all of it;
each life is one turn of it in a different body, and a fill or a switch-up hands each to the next.
The cat recordings (samples/cat/, as instruments in meows.py) come in as the coil's company.

  1 Kitten      piano and the coil's first phrase; kitten mews answer in its held notes
  2 Prowl       half-time trip-hop, a plucked fretless on the ground; frozen meows a third over the coil
  3 Hunt        four on the floor, acid and synth pluck; the meow takes the theme: the first solo
  4 Pounce      a double-time break, brass stabs; partials meows gathered on the chords, a mew hook;
                a stutter and a dead stop
  5 Nine Tails  the metal riff, double kick; the coil's climax with meows a third over it, a yowl at the top
  6 Catnap      the bottom drops out: rain, a heartbeat, the coil hands its phrase to a meow halfway,
                sighs on the sighs
  7 Ninth Life  the ground in 7/8 (2+2+3): the coil falls chromatically with the bass, mews on the accents
  8 Landing     back in 4, everything: the riff, brass, choir, the coil's phrase in octaves with meows in
                thirds, the yowl on the Bb
  9 Purr        a purr (the arc at ~25 pulses a second, low-passed, breathing), the piano, and the phrase
                resolved: Bb to A, down to C#, to D; the last meow lands on D

Tracks (GUI numbering): 1 kick (duck key) 2 drums 3 bass 4 coil 5 meows 6 keys/guitar/brass/strings/fx

    python3 tools/music/nine_lives.py > projects/nine_lives.sfx
"""
from sfxsong import Song, CHORD, ticker_pitch
from meows import meow, mew, meow_partials, meow_hold

BPM = 112
s = Song("Nine Lives", BPM, seed=9_2026_11,
         comment="D minor, 112 BPM, part III of The Coil Forgets: nine variations, by Claude. tracks: 1 kick 2 drums 3 bass 4 coil 5 meows 6 keys/guitar/brass/fx",
         tracks=[(0.45, 0), (0.7, 0), (0.4, 0), (0.7, 0), (1.0, 0), (0.55, 0)])
R = s.R
DUCK = dict(duck=0.45, duck_from=1)
SOLO = 1.0          # the meow solo in Hunt
TREBLE = "luminal/luminal_synth_vocal_treble_00004.ogg"
GTR = "resonant/guitar_heavy_00002.ogg"

GROUND = [(-7, (5, 8, 12), 5, "minor"),       # Dm
          (-8, (7, 12, 16), 0, "major"),      # A/C#
          (-9, (8, 12, 17), 5, "minor"),      # Dm/C
          (-10, (5, 10, 14), 10, "major"),    # G/B
          (-11, (5, 10, 13), 10, "minor"),    # Gm/Bb
          (-12, (7, 12, 17), 0, "sus4"),      # Asus4
          (-12, (7, 12, 16), 0, "major"),     # A
          (-12, (10, 16, 19), 0, "harm7")]    # A7
# chord tones (pitch classes from A) per bar, for harmonising
TONES = [{5, 8, 0}, {0, 4, 7}, {5, 8, 0, 3}, {10, 2, 5}, {10, 1, 5}, {0, 5, 7}, {0, 4, 7}, {0, 4, 7, 10}]
SCALE = {5, 7, 8, 10, 0, 1, 3, 4}             # D E F G A Bb C C#: D minor with both sevenths


def hum(beat, amount=0.015):
    return beat + R.uniform(-amount, amount)


class Life:
    """One turn of the ground: eight bars of `bar` beats from `start`."""
    def __init__(self, start, bar=4.0):
        self.start, self.bar = start, bar

    def at(self, b, beat=0.0):
        return self.start + b * self.bar + beat

    @property
    def end(self):
        return self.start + 8 * self.bar


# ---- keys -----------------------------------------------------------------------------
def piano(beat, pitch, level, beats=4.0):
    s.clip("piano", "tones", 5, hum(beat), beats, level=level, attack=0.004, release="full", pitch=pitch,
           interval=12, timbre=0.0, detune=0.04, fm_ratio=1.0, fm_depth=1.1, env_curve=0.45, cutoff=0.5,
           env_cut=0.3, mod_decay=0.45, reverb=0.5, pan=max(-0.4, min(0.4, (pitch - 10) / 40)))


def piano_ground(L, level=0.42, density=3):
    for b in range(8):
        bass, upper, _, _ = GROUND[b]
        piano(L.at(b), bass, level * 1.1, beats=6)
        piano(L.at(b), bass + 12, level * 0.35, beats=6)
        for i, p in enumerate(upper[:density]):
            piano(L.at(b, (i + 1) * L.bar / 4), p, level * (0.62 - 0.08 * i), beats=5)


def pluck_arp(L, level=0.28, step=0.5):
    pat = [0, 1, 2, 1, 2, 0, 1, 2]
    for b in range(8):
        upper = GROUND[b][1]
        for i in range(int(round(L.bar / step))):
            p = upper[pat[i % 8]] + 12 + (12 if i % 8 >= 6 else 0)
            s.clip("pluck", "tones", 5, L.at(b, i * step), step * 2.5, level=level * (1 if i % 2 == 0 else 0.75),
                   attack=0.002, release="full", pitch=p, interval=12, timbre=1.0, detune=0.25, cutoff=0.3,
                   resonance=0.45, env_curve=0.4, env_cut=0.5, mod_decay=0.1, echo=0.22, reverb=0.3,
                   pan=0.3 if i % 2 else -0.3, **DUCK)


def brass_stab(beat, beats, b, level=0.26):
    for p, pan in zip(GROUND[b % 8][1], (-0.3, 0.0, 0.3)):
        s.clip("brass", "tones", 5, beat, beats, level=level, attack=0.03, release=min(0.25, beats * 0.12),
               pitch=p + 12, interval=-12, timbre=1.0, detune=0.15, cutoff=0.3, resonance=0.15, drive=0.25,
               env_cut=0.45, decay=0.3, sus_level=0.65, reverb=0.35, pan=pan)


def strings(L, level=0.24, cut=0.58):
    for b in range(8):
        _, _, root, q = GROUND[b]
        s.clip("strings", "cloud", 5, L.at(b) - 0.2, L.bar + 0.6, level=level, attack=0.3, release=0.5,
               pitch=root, voices=10, gather=1.0, drift=0.1, timbre=1.0, chord=CHORD[q], cutoff=cut,
               reverb=0.5, **DUCK)


CHOIR_PITCH = {5: 2.06, 0: -2.94, 10: -4.94}


def choir(L, level=0.36):
    for b in range(8):
        _, _, root, q = GROUND[b]
        s.clip("choir", "choir", 5, L.at(b) - 0.2, L.bar + 0.6, level=level, attack=0.3, release=0.5,
               pitch=CHOIR_PITCH[root], chord=CHORD["minor" if q == "minor" else "major"], file=TREBLE,
               start=0.2, speed=0.3, voices=8, gather=1.0, drift=0.4, wander=2, scatter=0.4, reverb=0.6,
               cutoff=0.75)


# ---- bass -------------------------------------------------------------------------------
ACID = [(0, 0, 1.0), (0.5, 0, 0.5), (1, 12, 0.7), (1.5, 0, 0.5), (2, 0, 0.9), (2.5, 7, 0.6), (3, 12, 0.8), (3.5, 0, 0.6)]
ACID7 = [(0, 0, 1.0), (0.5, 0, 0.5), (1, 12, 0.9), (1.5, 0, 0.5), (2, 0, 1.0), (2.5, 7, 0.6), (3, 12, 0.7)]
CHUG = [(0, 1.0), (0.75, 0.5), (1.5, 0.7), (2, 0.9), (2.75, 0.5), (3, 0.8), (3.5, 0.6)]


def acid(L, cut=0.2, drive=0.4, level=0.36, pat=ACID):
    for b in range(8):
        bass = GROUND[b][0]
        for at, off, acc in pat:
            s.clip("acid", "tones", 2, L.at(b, at), 0.45, level=level * (0.75 + 0.25 * acc), attack=0.003,
                   release=0.06, pitch=bass + off, interval=0, timbre=1.0, cutoff=cut, resonance=0.78,
                   drive=drive, env_cut=0.25 + 0.35 * acc, mod_decay=0.14, **DUCK)


def fretless(L, level=1.0):
    """A plucked fretless on the ground: the root, its octave, a slide into the next bar."""
    for b in range(8):
        bass = GROUND[b][0]
        nxt = GROUND[(b + 1) % 8][0]
        for at, off, ln in ((0, 0, 1.5), (1.5, 12, 0.5), (2, 0, 1.0), (3, 7 if nxt == bass else 0, 1.0)):
            p = bass + off
            s.clip("fretless", "pluck", 2, L.at(b, at), ln + 0.3, level=level, attack=0.003, release="full",
                   pitch=p - 0.3, pitch_swp=0.3 + ((nxt - bass) if at == 3 and nxt != bass else 0),
                   damp=0.75, sustain=0.93, pick=0.3, cutoff=0.45)
            s.clip("fretless_body", "tones", 2, L.at(b, at), ln + 0.3, level=level * 0.8, attack=0.01,
                   release="full", pitch=p - 0.3, pitch_swp=0.3, interval=12, timbre=2.0, env_curve=0.3)


def riff(L, level=0.5):
    for b in range(8):
        shift = GROUND[b][0] + 7
        for at, acc in CHUG:
            long = at in (0, 2)
            s.clip("gtr", "sample", 5, L.at(b, at), 0.9 if long else 0.4, level=level * (0.7 + 0.3 * acc),
                   attack=0.002, release=0.12 if long else 0.06, pitch=shift, file=GTR,
                   cutoff=0.62 if long else 0.48, drive=0.3, pan=-0.35 if b % 2 else 0.35)


# ---- drums -------------------------------------------------------------------------------
def kick(beat, vel=1.0, dark=False):
    s.clip("kick", "tones", 0, beat, 0.6, level=0.8 * vel, attack=0.001, release="full", pitch=3,
           pitch_swp=-22, interval=0, timbre=0.0, drive=0.5, env_curve=0.35, cutoff=0.35 if dark else 1.0,
           keyed=0)


def snare(beat, vel=1.0, tune=0.0, body=True, verb=0.25):
    s.clip("snare", "noise", 1, beat, 0.5, level=min(1.0, 0.85 * vel), attack=0.001, release="full",
           filter=1, cutoff=0.74, resonance=0.2, color=0.3, env_curve=0.6, reverb=verb, keyed=0)
    if body:
        s.clip("snare_body", "tones", 1, beat, 0.3, level=min(1.0, 0.65 * vel), attack=0.001, release="full",
               pitch=9 + tune, pitch_swp=-7, interval=7, timbre=0.0, drive=0.35, env_curve=0.5, keyed=0)


def rim(beat, vel=1.0):
    s.clip("rim", "tones", 1, beat, 0.15, level=0.5 * vel, attack=0.0, release="full", pitch=31,
           interval=7, timbre=0.0, fm_ratio=2.3, fm_depth=1.0, env_curve=0.8, reverb=0.3, keyed=0)


def tom(beat, pitch, vel=1.0):
    s.clip("tom", "tones", 1, beat, 1.0, level=0.6 * vel, attack=0.001, release="full", pitch=pitch,
           pitch_swp=-5, interval=0, timbre=2.0, fm_ratio=1.4, fm_depth=1.2, env_curve=0.5, reverb=0.3, keyed=0)


def crash(beat, level=0.25):
    s.clip("crash", "noise", 1, beat, 8, level=level, attack=0.001, release="full", filter=2, cutoff=0.8,
           color=0.0, env_curve=0.75, reverb=0.45, pan=0.2, keyed=0)


def open_hat(beat, level=0.18):
    s.clip("open_hat", "noise", 1, beat, 0.4, level=level, attack=0.002, release="full", filter=2,
           cutoff=0.86, color=0.0, env_curve=0.4, pan=0.3, keyed=0)


def hats(start, beats, per_beat=4, misfire=0.3, level=0.5):
    s.clip("coil_hats", "arc", 1, start, beats, level=level, attack=0.0, release=0.05,
           pitch=ticker_pitch(BPM, per_beat), divide=4, burst=1.2, snap=0.85, misfire=misfire, filter=2,
           cutoff=0.74, pan=-0.25, keyed=0)


def swell(end_beat, beats, level=0.2):
    """A reversed-cymbal swell that lands on end_beat."""
    s.clip("swell", "noise", 1, end_beat - beats, beats, level=level, attack=min(2.0, beats * s.spb * 0.95),
           release=0.02, filter=2, cutoff=0.7, cutoff_swp=0.15, color=0.0, reverb=0.3, keyed=0)


# fills: each takes the beat it starts on and lasts to the end of its bar
def fill_drag(t, beats=2):
    for i in range(int(beats / 0.125)):
        snare(t + i * 0.125, 0.3 + 0.6 * i / (beats / 0.125), tune=i * 0.4, body=i % 2 == 0)


def fill_toms(t, beats=2, step=0.25):
    pitches = [8, 6, 3, 1, -2, -4, -6, -8]
    for i in range(int(beats / step)):
        tom(t + i * step, pitches[i % 8], 0.7 + 0.03 * i)


def fill_stutter(t, beats=1.5):
    for i in range(int(beats / 0.0625)):
        snare(t + i * 0.0625, 0.5 + 0.4 * i / (beats / 0.0625), tune=i * 0.5, body=i % 3 == 0, verb=0.1)


def fill_blast(t, beats=2):
    for i in range(int(beats / 0.125)):
        kick(t + i * 0.125, 0.55)
        if i % 2 == 0:
            snare(t + i * 0.125, 0.7, body=i % 4 == 0)


AMEN = [(0, "k", 1), (2, "k", .8), (4, "s", 1), (7, "s", .45), (9, "s", .55), (10, "k", .9),
        (11, "k", .7), (12, "s", 1), (15, "s", .5)]


def amen(t, step=0.125, vel=1.0):
    """The break over two beats (sixteenths of a double-time bar)."""
    for st, inst, v in AMEN:
        if inst == "k":
            kick(t + st * step, v * vel)
        else:
            snare(t + st * step, v * vel, body=v > 0.6)


# ---- voices --------------------------------------------------------------------------------
def voice(beat, beats, pitch, level=0.5, misfire=0.0, flicker=0.12, jitter=0.02, drive=0.0, burst=14, pan=0.05):
    ring = 0.5
    long = beats >= 2
    s.clip("voice", "arc", 3, hum(beat), beats + ring, level=level, attack=0.12 if long else 0.05,
           release=min(0.8, (beats + ring) * s.spb * 0.45), pitch=pitch, ring=0.95, formant=12, burst=burst,
           jitter=jitter, misfire=misfire, flicker=flicker, snap=0.1, color=0.3, drive=drive, reverb=0.45,
           echo=0.15, pan=pan, **({"lfo_rate": 4.8, "lfo>pitch": 0.1} if long else {}))


def third_above(p, b):
    """The diatonic third over p (3 or 4 semitones), preferring a chord tone of bar b."""
    for d in (3, 4):
        if (p + d) % 12 in TONES[b % 8]:
            return p + d
    for d in (3, 4):
        if (p + d) % 12 in SCALE:
            return p + d
    return p + 3


# part I's phrases; here each of its beats is two (one bar of the ground = two of its beats)
P1 = [(0.5, 1, 20), (1.5, .5, 19), (2, 1, 19), (3, .5, 17), (3.5, .5, 16), (4, 1.5, 17),
      (5.5, .5, 24), (6, 1, 24), (7, 1, 22), (8, 1.5, 25), (9.5, .5, 24), (10, 1, 22), (11, 1, 20),
      (12, 4, 19)]
P2 = [(0, 1.5, 25), (1.5, .5, 24), (2, 1, 24), (3, 1, 22), (4, 1.5, 20), (5.5, .5, 27),
      (6, 1, 26), (7, 1, 29), (8, 1.5, 29), (9.5, .5, 27), (10, 1, 25), (11, 1, 24),
      (12, 2, 19), (14, 2, 16)]
P3 = [(0, 2, 29), (2, 1.5, 28), (3.5, .5, 31), (4, 1.5, 32), (5.5, .5, 31), (6, 1.5, 29),
      (7.5, .5, 31), (8, 1, 34), (9, 1, 32), (10, 1, 29), (11, 1, 27), (12, 4, 28)]


def notes(L, phrase, shift=0):
    """(beat, beats, pitch, bar) for a part-I phrase laid over a 4/4 life."""
    for b, ln, p in phrase:
        yield L.start + 2 * b, 2 * ln, p + shift, int(2 * b // 4)


def silence(a, b):
    """A dead stop from beat a to beat b: drop what starts inside, cut what sounds into it."""
    a_s, b_s = a * s.spb, b * s.spb
    kept = []
    for c in s.clips:
        start, dur, name, typ, track, seed, kw = c
        if a_s <= start < b_s:
            continue
        if start < a_s < start + dur:
            dur = a_s - start
            kw = dict(kw, release=min(kw.get("release", 0.25) if not isinstance(kw.get("release"), str) else dur, dur * 0.5))
        kept.append((start, dur, name, typ, track, seed, kw))
    s.clips[:] = kept


# =================================================================================================
lives = []
t = 0.0
for bar in (4, 4, 4, 4, 4, 4, 3.5, 4, 4):
    lives.append(Life(t, bar))
    t += 8 * bar
L1, L2, L3, L4, L5, L6, L7, L8, L9 = lives

# ---- 1 Kitten: piano, the coil's phrase, kitten mews answering in its held notes
piano_ground(L1, level=0.42, density=2)
for beat, ln, p, _ in notes(L1, P1):
    voice(beat, ln, p, level=0.45)
for at, ps in ((9, (31, 29, 28)), (17, (36, 34)), (26, (32, 31, 29, 28))):   # echoes an octave up
    for i, p in enumerate(ps):
        mew(s, 4, L1.start + at + 0.5 * i, p, k=i, level=0.68, pan=0.35 - 0.2 * i)
meow(s, 4, L1.start + 29.5, 2, 24, "sigh", level=0.68, pan=-0.2)         # sinking onto A over the A chord
swell(L2.start, 4, level=0.15)

# ---- 2 Prowl: half-time trip-hop
for b in range(8):
    kick(L2.at(b), 0.9); kick(L2.at(b, 0.75), 0.4); kick(L2.at(b, 2.5), 0.7)
    snare(L2.at(b, 2), 0.85, verb=0.45)
    rim(L2.at(b, 1.5), 0.6); rim(L2.at(b, 3.25), 0.4)
hats(L2.start, 7 * 4 + 2, per_beat=2, misfire=0.3, level=0.4)
fretless(L2)
piano_ground(L2, level=0.3, density=1)
for beat, ln, p, b in notes(L2, P2):
    voice(beat, ln, p, level=0.48)
    if ln >= 2 and beat >= L2.start + 8:           # frozen meows a third above the coil's long notes
        meow_hold(s, 4, beat, ln + 0.2, third_above(p, b) + 12, "kitty", at=0.4, level=0.48, pan=0.3)
fill_drag(L2.at(7, 2), 1.5)
swell(L3.start, 2, level=0.2)

# ---- 3 Hunt: four on the floor; the meow takes the theme
crash(L3.start, 0.22)
for b in range(8):
    for k in range(4):
        kick(L3.at(b, k), 0.9)
        open_hat(L3.at(b, k + 0.5), 0.15)
    if b < 7:
        snare(L3.at(b, 1), 0.8, body=False); snare(L3.at(b, 3), 0.9)
hats(L3.start, 7 * 4, per_beat=4, misfire=0.3, level=0.45)
acid(L3, cut=0.18)
pluck_arp(L3)
for beat, ln, p, b in notes(L3, P1, shift=12):     # the solo: an octave up, where meows live
    which = "kitty" if ln < 2 else "garage1"
    meow(s, 4, beat, ln, p, which, level=SOLO)
    if ln >= 3:                                      # a long note: hold it after the meow's own vowel
        meow_hold(s, 4, beat + 0.6, ln - 0.6, p, "kitty", at=0.5, level=SOLO * 0.65)
for b in (3, 7):                                     # the coil answers under it
    voice(L3.at(b, 2), 2, GROUND[b][1][2] + 12, level=0.28)
fill_toms(L3.at(7, 2), 2)

# ---- 4 Pounce: a double-time break; partials meows on the chords; a mew hook
crash(L4.start, 0.25)
for b in range(8):
    for half in (0, 2):
        if not (b == 7 and half == 2):
            amen(L4.at(b, half))
    brass_stab(L4.at(b), 1.0, b)
    if b % 2 == 1:
        brass_stab(L4.at(b, 2.5), 0.5, b, level=0.2)
    _, _, root, q = GROUND[b]
    meow_partials(s, 4, L4.at(b) - 0.1, L4.bar + 0.3, root + 24, "kitty", level=0.45, speed=0.15, gather=0.9,
                  chord=CHORD["minor" if q == "minor" else "major"])
hats(L4.start, 7 * 4, per_beat=8, misfire=0.45, level=0.4)
acid(L4, cut=0.24, drive=0.55)
strings(L4, level=0.2)
for beat, ln, p, _ in notes(L4, P2):
    voice(beat, ln, p, level=0.3, drive=0.15)
for b in range(4, 7):                                # the hook: mews up the chord in sixteenths
    for i in range(8):
        tone = GROUND[b][1][i % 3] + 24 + (12 if i % 4 == 3 else 0)
        while tone > 37:
            tone -= 12
        mew(s, 4, L4.at(b, 2 + i * 0.25), tone, k=i % 4, level=0.57, pan=0.4 if i % 2 else -0.4)
fill_stutter(L4.at(7, 2), 1.0)                      # ...and a dead stop on the last beat

# ---- 5 Nine Tails: the riff; the coil's climax with meows a third over it
crash(L5.start, 0.3)
s.clip("impact", "sample", 5, L5.start, 6, level=0.5, release=2.0, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
for b in range(8):
    for at, acc in CHUG:
        if acc >= 0.7:
            kick(L5.at(b, at), 0.6 + 0.35 * acc)
    snare(L5.at(b, 1)); snare(L5.at(b, 3))
    if b % 2 == 1 and b < 7:                         # double-kick bursts
        for i in range(4):
            kick(L5.at(b, 3 + 0.25 * i), 0.55)
    brass_stab(L5.at(b), 1.0, b, level=0.28)
riff(L5)
acid(L5, cut=0.3, drive=0.7, level=0.3, pat=[(at, 0, acc) for at, acc in CHUG])
hats(L5.start, 8 * 4, per_beat=4, misfire=0.1, level=0.55)
choir(L5, level=0.32)
for beat, ln, p, b in notes(L5, P3):
    voice(beat, ln, p, level=0.3, drive=0.45, burst=9)
    voice(beat, ln, p - 12, level=0.2, drive=0.3, burst=9, pan=-0.3)
    h = third_above(p, b)
    meow(s, 4, beat, ln, h, "garage1", level=0.63, pan=0.3)
    if ln >= 2:
        meow_hold(s, 4, beat + 0.5, ln - 0.3, h, "kitty", at=0.5, level=0.45, pan=0.3)
meow(s, 4, L5.start + 16 - 0.25, 2, 34, "angry", level=0.75, pan=-0.1)   # the yowl, on the G at the top
fill_blast(L5.at(7, 2), 2)

# ---- 6 Catnap: the bottom drops out
crash(L6.start, 0.2)
s.clip("rain", "noise", 5, L6.start, 8 * 4, level=0.16, attack=2.0, release=3.0, filter=2, cutoff=0.6,
       color=0.4, reverb=0.3, lfo_rate=0.13, **{"lfo>amp": 0.25}, keyed=0)
piano_ground(L6, level=0.42, density=3)
for b in range(8):
    kick(L6.at(b), 0.35, dark=True); kick(L6.at(b, 0.75), 0.25, dark=True)
    _, _, root, q = GROUND[b]
    meow_partials(s, 4, L6.at(b) - 0.1, 4.3, root + 24, "garage1", level=0.33, speed=0.1, gather=0.95,
                  chord=CHORD["minor" if q == "minor" else "major"])
for beat, ln, p, b in notes(L6, P1):
    if beat < L6.start + 16:
        voice(beat, ln, p, level=0.45)              # the coil sings the first half
    else:
        meow(s, 4, beat, ln, p + 12, "kitty", level=0.75)       # and the meow sings the rest
        if ln >= 2:
            meow_hold(s, 4, beat + 0.6, ln - 0.4, p + 12, "kitty", at=0.5, level=0.42)
for at, lo in ((16 - 0.5, 36), (20 - 0.5, 34)):       # sighs on the sighs
    meow(s, 4, L6.start + at, 2.5, lo, "sigh", level=0.6, pan=-0.3)
s.clip("riser", "noise", 5, L6.at(6), 8, level=0.2, attack=2.0, release=0.05, filter=2, cutoff=0.4,
       cutoff_swp=0.45, color=0.0, reverb=0.3)

# ---- 7 Ninth Life: the ground in 7/8
crash(L7.start, 0.25)
for b in range(8):
    kick(L7.at(b), 1.0); snare(L7.at(b, 1)); kick(L7.at(b, 2), 0.9); kick(L7.at(b, 2.5), 0.6); snare(L7.at(b, 3), 0.9)
    for i, at in enumerate((0, 1, 2)):               # mews on the 2+2+3 accents
        tone = GROUND[b][1][i] + 24
        mew(s, 4, L7.at(b, at), tone - 12 if tone > 37 else tone, k=i, level=0.6, pan=(-0.4, 0.0, 0.4)[i])
    voice(L7.at(b), 3.2, GROUND[b][0] + 36, level=0.28, drive=0.1)   # the coil falls with the bass
hats(L7.start, 8 * 3.5, per_beat=4, misfire=0.25, level=0.45)
acid(L7, cut=0.26, drive=0.5, pat=ACID7)
pluck_arp(L7, level=0.24)
strings(L7, level=0.2)
fill_toms(L7.at(7, 1.5), 2)

# ---- 8 Landing: everything
crash(L8.start, 0.3)
s.clip("impact2", "sample", 5, L8.start, 6, level=0.5, release=2.0, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
for b in range(8):
    for at, acc in CHUG:
        if acc >= 0.7:
            kick(L8.at(b, at), 0.6 + 0.35 * acc)
    snare(L8.at(b, 1)); snare(L8.at(b, 3))
    brass_stab(L8.at(b), 1.0, b, level=0.28)
riff(L8)
acid(L8, cut=0.32, drive=0.7, level=0.3, pat=[(at, 0, acc) for at, acc in CHUG])
hats(L8.start, 8 * 4, per_beat=4, misfire=0.05, level=0.55)
choir(L8, level=0.36)
strings(L8, level=0.22, cut=0.65)
for beat, ln, p, b in notes(L8, P1, shift=12):
    voice(beat, ln, p, level=0.25, drive=0.35, burst=10)
    voice(beat, ln, p - 12, level=0.17, drive=0.25, burst=10, pan=-0.3)
    h = third_above(p, b)
    meow(s, 4, beat, ln, h, "garage1", level=0.63, pan=0.3)
    meow(s, 4, beat, ln, p, "kitty", level=0.52, pan=-0.2)
    if ln >= 2:
        meow_hold(s, 4, beat + 0.5, ln - 0.3, h, "kitty", at=0.5, level=0.42, pan=0.3)
meow(s, 4, L8.start + 16 - 0.25, 2, 37, "angry", level=0.75)              # the yowl on the Bb
fill_drag(L8.at(7, 2), 2)
swell(L9.start, 4, level=0.2)

# ---- 9 Purr: the phrase resolved
t9 = L9.start
s.clip("purr", "arc", 5, t9, 8 * 4 + 6, level=0.5, attack=2.0, release=3.0, pitch=-25.6, ring=0.0, burst=6,
       jitter=0.15, snap=0.3, flicker=0.2, color=0.7, filter=0, cutoff=0.42, resonance=0.3, lfo_rate=0.45,
       **{"lfo>amp": 0.5}, keyed=0)
crash(t9, 0.2); kick(t9, 0.8)
piano_ground(L9, level=0.4, density=2)
done = [n for n in P1 if n[0] < 12] + [(12, 2, 19), (14, 2, 16)]
for beat, ln, p, _ in notes(L9, done):
    voice(beat, ln, p, level=0.45)
end = L9.end
piano(end, -7, 0.45, beats=10); piano(end, 5, 0.25, beats=10); piano(end + 1, 8, 0.25, beats=9)
voice(end, 10, 17, level=0.45)                       # the coil's D
meow(s, 4, end + 2, 3, 29, "sigh", level=0.6, pan=0.2)     # and the last meow, sinking onto D
meow_hold(s, 4, end + 3, 6, 29, "kitty", at=0.5, level=0.3, pan=0.2, release=2.5)

silence(L4.at(7, 3), L5.start)                      # Pounce's dead stop

if __name__ == "__main__":
    s.write()
