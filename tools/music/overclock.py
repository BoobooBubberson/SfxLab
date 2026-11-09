#!/usr/bin/env python3
"""'Overclock' - F minor, 174 BPM. Drum and bass that comes apart into breakcore.

Combat music. A programmed amen-style break, a two-saw reese bass gliding between
chords, a Tesla-coil lead whose formant moves every note (vowels), a self-playing coil
hi-hat ticker, and a choir that starts as chaos and gathers onto F minor in the
breakdown. Drop B chops the break apart, doubles the ticker to 32nds and harmonises
the lead a diatonic third below. The ending is a tape stop.

Tracks (GUI numbering): 1 kick (duck key) 2 drums 3 bass 4 lead 5 pads/choir 6 fx.
Kept as stems so a game can bring layers in and out by intensity.

    python3 overclock.py > projects/overclock.sfx
"""
from sfxsong import Song, ticker_pitch, CHORD

BPM = 174
s = Song("Overclock", BPM, seed=174_2026,
         comment="F minor, 174 BPM, by Claude. tracks: 1 kick 2 drums 3 bass 4 lead 5 pads/choir 6 fx",
         tracks=[(0.55, 0), (1.0, 0), (0.37, 0), (0.65, 0), (0.75, 0), (0.7, 0)])
R = s.R
BAR = 4
ST = 0.25            # a sixteenth, in beats
DUCK = dict(duck=0.6, duck_from=1)
# levels, set by measuring the stems (tools/music/listen.py)
KICK, SNARE, REESE, SUB = 0.55, 0.55, 0.42, 0.45

# sections, in bars
INTRO, BUILD, DROP_A, BREAK, DROP_B, OUTRO = 0, 4, 8, 16, 20, 28
END = 30

# ---- drums -------------------------------------------------------------------------
def kick(beat, vel=1.0):
    s.clip("kick", "tones", 0, beat, 0.75, level=KICK * vel, attack=0.001, release="full", pitch=3,
           pitch_swp=-22, interval=0, timbre=0.0, drive=0.65, env_curve=0.35, keyed=0)

def snare(beat, vel=1.0, body=True, tune=0.0, hp=None):
    extra = dict(filter=2, cutoff=hp) if hp is not None else dict(filter=1, cutoff=0.74)
    s.clip("snare", "noise", 1, beat, 0.55, level=SNARE * vel, attack=0.001, release="full",
           resonance=0.2, color=0.3, env_curve=0.6, reverb=0.2, pan=0.05, keyed=0, **extra)
    if body:
        s.clip("snare_body", "tones", 1, beat, 0.3, level=SNARE * 0.7 * vel, attack=0.001, release="full",
               pitch=9 + tune, pitch_swp=-7, interval=7, timbre=0.8, drive=0.35, env_curve=0.5,
               keyed=0)

AMEN_A = [(0, "k", 1), (2, "k", .8), (4, "s", 1), (7, "s", .45), (9, "s", .55), (10, "k", .9),
          (11, "k", .7), (12, "s", 1), (15, "s", .5)]
AMEN_B = [(0, "k", 1), (2, "k", .8), (4, "s", 1), (7, "s", .45), (9, "s", .55), (10, "k", .9),
          (12, "s", 1), (14, "s", .4), (15, "s", .7)]

def break_bar(bar, pattern, hp=None, kicks=True):
    for step, inst, vel in pattern:
        b = bar * BAR + step * ST
        if inst == "k" and kicks:
            kick(b, vel)
        elif inst == "s":
            snare(b, vel, body=vel > 0.5, hp=hp)

def roll(beat, beats, div, v0, v1, tune0=0, tune1=12, body_every=2):
    """A snare roll from v0 to v1 in level, rising in pitch."""
    n = int(round(beats / div))
    for i in range(n):
        u = i / max(1, n - 1)
        snare(beat + i * div, v0 + (v1 - v0) * u, body=(i % body_every == 0),
              tune=tune0 + (tune1 - tune0) * u)

def chopped_bar(bar):
    """Breakcore: an amen bar with its second half replaced by a random chop."""
    base = AMEN_A if bar % 2 == 0 else AMEN_B
    chop = R.choice(["roll32", "ruffle", "stutter", "keep", "half"])
    for step, inst, vel in base:
        if chop != "keep" and step >= 8:
            continue
        b = bar * BAR + step * ST
        kick(b, vel) if inst == "k" else snare(b, vel, body=vel > 0.5)
    t = bar * BAR + 2
    if chop == "roll32":
        roll(t, 2, 0.125, 0.35, 1.0, 0, 9, body_every=4)
    elif chop == "ruffle":            # kick ruffle then two hard snares
        for i in range(6):
            kick(t + i * 0.125, 0.55 + 0.07 * i)
        snare(t + 1.0, 1.0); snare(t + 1.5, 0.9, tune=3)
    elif chop == "stutter":           # one snare retriggered at 16ths, falling in pitch
        for i in range(8):
            snare(t + i * ST, 0.9 - 0.05 * i, body=True, tune=-i * 1.5)
    elif chop == "half":              # half-time stomp
        kick(t, 1.0); snare(t + 1.0, 1.0); kick(t + 1.5, 0.7)

def ticker(bar0, bars, per_beat=4, misfire=0.3, level=0.3, swp=0.0):
    s.clip("coil_hats", "arc", 1, bar0 * BAR, bars * BAR, level=min(1.0, level * 1.8), attack=0.0, release=0.05,
           pitch=ticker_pitch(BPM, per_beat), pitch_swp=swp, divide=4, burst=1.4, snap=0.85,
           misfire=misfire, filter=2, cutoff=0.72, pan=-0.25, keyed=0)

def crash(beat, level=0.35):
    s.clip("crash", "noise", 1, beat, 6, level=level, attack=0.001, release="full", filter=2,
           cutoff=0.8, color=0.0, env_curve=0.7, reverb=0.45, pan=0.2, keyed=0)

def impact(beat):
    s.clip("impact", "sample", 5, beat, 8, level=0.6, release=2.5, file="gravitic/gravitic_impact_00001.ogg",
           keyed=0)
    s.clip("boom", "tones", 0, beat, 3, level=KICK, attack=0.001, release="full", pitch=0,
           pitch_swp=-24, interval=0, timbre=0.0, drive=0.4, env_curve=0.2, reverb=0.3, keyed=0)

# ---- harmony -------------------------------------------------------------------------
# two bars per chord: (name, bass root in semitones from A2, quality)
PROG = [("Fm", -16, "minor"), ("Db", -8, "major"), ("Bbm", -11, "minor"), ("C", -9, "major")]

def reese(beat, beats, root, glide=0.0, cut=0.42, drive=0.45, level=None):
    level = REESE if level is None else level
    s.clip("reese", "tones", 2, beat, beats, level=level, attack=0.004, release=min(0.35, beats * 0.12),
           pitch=root, pitch_swp=glide, interval=0, timbre=2.0, detune=0.45, cutoff=cut,
           resonance=0.25, drive=drive, phaser=0.25, lfo_rate=BPM / 60 / 2, **{"lfo>cut": 0.2}, **DUCK)

def sub(beat, beats, root, glide=0.0):
    s.clip("sub", "tones", 2, beat, beats, level=SUB, attack=0.004, release=min(0.3, beats * 0.12),
           pitch=root, pitch_swp=glide, interval=0, timbre=0.0, **DUCK)

# (step, length in steps, offset from root) over 2 bars = 32 sixteenths; last note glides
BASS = [(0, 6, 0), (6, 2, 12), (8, 6, 0), (14, 2, 7), (16, 4, 0), (20, 2, 12), (22, 2, 7),
        (24, 6, 0), (30, 2, 0)]

def bassline(bar0, cycles, drive=0.45):
    for c in range(cycles):
        for ci, (_, root, _) in enumerate(PROG):
            nxt = PROG[(ci + 1) % len(PROG)][1]
            b0 = (bar0 + c * 8 + ci * 2) * BAR
            for i, (st, ln, off) in enumerate(BASS):
                glide = (nxt - root) if i == len(BASS) - 1 else 0.0
                beat, beats = b0 + st * ST, ln * ST
                reese(beat, beats, root + off, glide, drive=drive)
                if ln >= 4 or i == len(BASS) - 1:
                    sub(beat, beats, root, glide)

def pad(bar0, cycles, level=0.17):
    for c in range(cycles):
        for ci, (_, root, q) in enumerate(PROG):
            r = root + 24                    # into the 3rd octave
            third = 3 if q == "minor" else 4
            b = (bar0 + c * 8 + ci * 2) * BAR
            for p, iv, pan in ((r, 7, -0.35), (r + third, 12, 0.35)):
                s.clip("pad", "tones", 4, b, 2 * BAR + 0.5, level=level, attack=0.08, release=0.6,
                       pitch=p, interval=iv, timbre=1.6, detune=0.3, shimmer=0.2, cutoff=0.45,
                       pan=pan, reverb=0.4, **DUCK)

# ---- lead -------------------------------------------------------------------------
# (beat in the 8-bar cycle, length, pitch). F4=20 G4=22 Ab4=23 Bb4=25 C5=27 Db5=28 Eb5=30 E5=31 F5=32 G5=34
MEL = [(0, 1.5, 27), (1.5, .5, 23), (2, 1, 20), (3, .5, 22), (3.5, .5, 23),
       (4, 1, 27), (5, .5, 28), (5.5, .5, 27), (6, 2, 23),
       (8, 1.5, 32), (9.5, .5, 30), (10, 1, 28), (11, .5, 27), (11.5, .5, 28),
       (12, 1, 30), (13, 1, 28), (14, 2, 20),
       (16, 1.5, 25), (17.5, .5, 28), (18, 1, 32), (19, .5, 30), (19.5, .5, 28),
       (20, 1, 27), (21, 1, 25), (22, 2, 28),
       (24, 1, 27), (25, .5, 31), (25.5, .5, 32), (26, 1, 34), (27, .5, 32), (27.5, .5, 31),
       (28, 4, 31)]
VOWELS = [9, 12, 14, 17, 19]          # formant, semitones over the note

def coil(beat, beats, pitch, level=0.42, pan=0.1, glide=0.0, vowel=None):
    ring = 0.25 if beats >= 1 else 0.12
    vib = {"lfo>pitch": 0.18, "lfo_rate": 5.5} if beats >= 1.5 else {}
    s.clip("coil", "arc", 3, beat, beats + ring, level=level, attack=0.01, release=ring + 0.05,
           pitch=pitch, pitch_swp=glide, ring=0.7, formant=vowel if vowel is not None else R.choice(VOWELS),
           burst=8, jitter=0.05, snap=0.4, flicker=0.25, drive=0.25, echo=0.2, reverb=0.3, pan=pan,
           **vib)

# pitch classes (semitones from A, mod 12) of F natural minor, and with E natural over C
SCALE = {8, 10, 11, 1, 3, 4, 6}
SCALE_C = (SCALE - {6}) | {7}

def third_below(p, beat_in_cycle):
    sc = SCALE_C if beat_in_cycle >= 24 else SCALE
    for d in (3, 4):
        if (p - d) % 12 in sc:
            return p - d
    return p - 3

def lead(bar0, harmony=False):
    for b, ln, p in MEL:
        beat = bar0 * BAR + b
        v = R.choice(VOWELS)
        coil(beat, ln, p, vowel=v)
        if harmony:
            coil(beat, ln, third_below(p, b), level=0.3, pan=-0.3, vowel=v)

# ---- fx / glitch ----------------------------------------------------------------------
VOX = "luminal/luminal__synth_vocal_base_00001.ogg"   # Bb3 -35c: -0.65 makes A, +7.35 makes F4

def glitch(beat, n, div, pitch0=7.35, step=0.0, start=0.3, level=0.4):
    """A frozen grain of the voice, retriggered: the stutter."""
    for i in range(n):
        s.clip("glitch", "sample", 5, beat + i * div, div, level=level, attack=0.0, release=0.01,
               pitch=pitch0 + i * step, file=VOX, pitch_mode=1, speed=0.0, start=start,
               cutoff=0.85, pan=(-0.4 if i % 2 else 0.4), keyed=0)

def riser(bar0, bars):
    s.clip("coil_riser", "arc", 5, bar0 * BAR, bars * BAR, level=0.33, attack=bars * BAR * 0.6 * s.spb,
           release=0.05, pitch=8, pitch_swp=24, ring=0.3, formant=19, burst=4, jitter=0.15,
           misfire=0.1, snap=0.5, flicker=0.4, reverb=0.3)

# =============================================================================
# arrangement
# =============================================================================
# intro: the reese alone, filter opening, glitch stutters, ticker misfiring less and less
s.clip("reese_intro", "tones", 2, 0, 4 * BAR, level=0.38, attack=1.5, release=0.3, pitch=-16,
       interval=12, timbre=2.0, detune=0.5, cutoff=0.2, cutoff_swp=0.25, resonance=0.35, drive=0.3,
       phaser=0.4, phaser_rate=0.3, lfo_rate=BPM / 60 / 4, **{"lfo>cut": 0.15})
for i, mis in enumerate([0.85, 0.65, 0.45, 0.3]):
    ticker(INTRO + i, 1, misfire=mis, level=0.22 + 0.03 * i)
glitch(1 * BAR + 3, 4, ST, step=0)
glitch(3 * BAR + 2, 8, ST / 2, step=1.0, level=0.35)

# build: break high-passed, opening up hit by hit; riser; roll accelerates
for i in range(3):
    bar = BUILD + i
    break_bar(bar, AMEN_A if i % 2 == 0 else AMEN_B, hp=0.6 - 0.18 * i, kicks=False)
ticker(BUILD, 4, misfire=0.25, level=0.3)
riser(BUILD + 2, 2)
roll(7 * BAR, 1, 0.5, 0.4, 0.5, 0, 2)
roll(7 * BAR + 1, 1, 0.25, 0.5, 0.7, 2, 6)
roll(7 * BAR + 2, 1.5, 0.125, 0.7, 1.0, 6, 12, body_every=3)
glitch(7 * BAR + 3.5, 4, 0.125, step=2.0, level=0.45)          # last half-beat: the voice only
s.clip("drone_build", "tones", 2, BUILD * BAR, 4 * BAR - 0.5, level=0.3, attack=2.0, release=0.2,
       pitch=-16, interval=12, timbre=2.0, detune=0.5, cutoff=0.45, cutoff_swp=0.3, resonance=0.3,
       drive=0.35)

# drop A
impact(DROP_A * BAR); crash(DROP_A * BAR)
for i in range(8):
    bar = DROP_A + i
    if i in (3, 7):                       # fill at the end of each 4 bars
        break_bar(bar, [x for x in AMEN_A if x[0] < 8])
        roll(bar * BAR + 2, 2, ST, 0.45, 1.0, 0, 8)
    else:
        break_bar(bar, AMEN_A if i % 2 == 0 else AMEN_B)
ticker(DROP_A, 8, misfire=0.2, level=0.3)
bassline(DROP_A, 1)
pad(DROP_A, 1)
lead(DROP_A)

# breakdown: chaos gathering into F minor; the reese held under it; stutters lead back in
s.clip("choir", "choir", 4, BREAK * BAR, 4 * BAR + 1, level=0.6, attack=2.0, release=1.0,
       pitch=-4.65, chord=CHORD["minor"], file=VOX, start=0.2, speed=0.35, voices=8, gather=0.0,
       gather_swp=1.0, drift=0.5, wander=12, scatter=0.4, reverb=0.6, cutoff=0.8)
s.clip("reese_hold", "tones", 2, BREAK * BAR, 4 * BAR - 0.5, level=0.34, attack=0.02, release=1.0,
       pitch=-16, interval=12, timbre=2.0, detune=0.5, cutoff=0.5, cutoff_swp=-0.3, resonance=0.3,
       drive=0.3, phaser=0.5, phaser_rate=0.2)
sub(BREAK * BAR, 4 * BAR - 0.5, -16)
ticker(BREAK, 3, misfire=0.7, level=0.2)
for k in range(4):                        # coil chant over the choir, one vowel per bar
    coil((BREAK + k) * BAR, 3, [27, 28, 25, 31][k], level=0.3, vowel=[9, 14, 19, 12][k])
riser(BREAK + 2, 2)
glitch((BREAK + 3) * BAR, 8, ST, step=-1.0, start=0.45)
glitch((BREAK + 3) * BAR + 2, 16, 0.125, step=0.75, start=0.45, level=0.45)

# drop B: chopped, harmonised, ticker doubled to 32nds
impact(DROP_B * BAR); crash(DROP_B * BAR)
for i in range(8):
    chopped_bar(DROP_B + i) if i not in (0, 4) else break_bar(DROP_B + i, AMEN_A)
ticker(DROP_B, 4, per_beat=8, misfire=0.5, level=0.25)
ticker(DROP_B + 4, 4, per_beat=8, misfire=0.25, level=0.27)
bassline(DROP_B, 1, drive=0.65)
pad(DROP_B, 1, level=0.15)
lead(DROP_B, harmony=True)
for bar in (DROP_B + 3, DROP_B + 7):
    glitch(bar * BAR + 3, 8, 0.125, pitch0=19.35, step=-1.0, start=0.3, level=0.3)

# outro: one last hit, then everything tape-stops
t = OUTRO * BAR
impact(t); kick(t); crash(t, 0.4)
s.clip("reese_stop", "tones", 2, t, 4, level=0.42, attack=0.004, release=0.6, pitch=-4,
       pitch_swp=-24, interval=12, timbre=2.0, detune=0.45, cutoff=0.5, cutoff_swp=-0.3, drive=0.5)
s.clip("sub_stop", "tones", 2, t, 4, level=0.45, attack=0.004, release=0.6, pitch=-16,
       pitch_swp=-12, interval=0, timbre=0.0)
coil(t, 4, 32, level=0.42, glide=-24, vowel=12)
coil(t, 4, 27, level=0.3, glide=-24, vowel=19, pan=-0.3)
ticker(OUTRO, 1, per_beat=8, misfire=0.2, level=0.27, swp=-30)
s.clip("choir_end", "choir", 4, t, 2 * BAR + 2, level=0.45, attack=0.02, release=3, pitch=7.35,
       chord=CHORD["minor"], file=VOX, start=0.2, speed=0.35, voices=8, gather=1.0, gather_swp=-1.0,
       drift=0.5, wander=12, scatter=0.4, reverb=0.6, cutoff=0.8)

if __name__ == "__main__":
    s.write()
