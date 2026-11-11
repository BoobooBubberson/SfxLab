#!/usr/bin/env python3
"""'Choir of Claws' - symphonic metal in D Phrygian, 140 BPM. The riff up front, cloud strings all through,
the cats as the choir.

  Intro        0-8    strings swell; the meow choir gathers out of a wander onto D; the riff muffled under it;
                      toms into
  Riff         8-16   the gallop riff: D with Phrygian hits on Eb and F, C Bb A Eb at its turn; double kick;
                      the strings stab its accents
  Verse        16-32  the riff palm-muted; the coil sings; a string ostinato; the meow choir underneath
  Pre-chorus   32-40  let-ring chords Bb C Bb C A; strings climbing; staccato meow clusters on the offbeats
  Chorus       40-56  Dm Bb F C Dm Bb Eb A, two bars each: the melody on a trio of meows in close harmony
                      (melody, a third under, the chord's root under that), the meow choir and the voice choir
                      on the chords, the high strings over all of it
  Breakdown    56-64  half time, syncopated chugs and dead stops, tremolo strings, the meow choir
  Solo         64-72  the cats shred: sixteenth-note arpeggios of meows up and down the chorus chords
  Last chorus  72-88  a half step up (Eb), everything
  Outro        88-92  the riff once more and the last chord, held by the choirs

Drums are noise only (noise_kit.py; the kick is the 0.7 / -1 sweep with resonance, brown noise and drive).
Held notes in the meows are the meow choir (a granular choir of one meow), never a frozen meow.
Tracks (GUI numbering): 1 kick (duck key) 2 drums 3 guitar/bass 4 coil/solo 5 meow trio/meow choir
6 strings/choir/fx

    python3 tools/music/choir_of_claws.py > projects/choir_of_claws.sfx
"""
from sfxsong import Song, CHORD
from meows import meow, mew, meow_choir
from noise_kit import Kit

BPM = 140
s = Song("Choir of Claws", BPM, seed=10_11_2026,
         comment="symphonic metal, D Phrygian, 140 BPM, by Claude. tracks: 1 kick 2 drums 3 guitar/bass 4 coil/solo 5 meows 6 strings/choir/fx",
         tracks=[(0.33, 0), (1.0, 0), (0.6, 0), (0.85, 0), (0.9, 0), (0.6, 0)])
R = s.R
BAR = 4
ST = 0.25                                   # a sixteenth
DUCK = dict(duck=0.35, duck_from=1)
kit = Kit(s, kick_track=0, track=1, kick_res=0.6, kick_color=0.85, kick_drive=0.4)
GTR = "resonant/guitar_heavy_00002.ogg"     # D power chord: pitch 0 = D
TREBLE = "luminal/luminal_synth_vocal_treble_00004.ogg"   # C4: -2.94 makes A3
KEY = 0                                     # the last chorus sets this to 1 (Eb)

# chords: name -> (root at octave 3 in semitones from A2, quality, power-chord shift from D)
CH = {"Dm": (5, "minor", 0), "Bb": (1, "major", -4), "F": (8, "major", 3), "C": (3, "major", -2),
      "Eb": (6, "major", 1), "A": (0, "major", -5), "Ebm": (6, "minor", 1)}
CHORUS = ["Dm", "Bb", "F", "C", "Dm", "Bb", "Eb", "A"]          # two bars each
PRE = ["Bb", "Bb", "C", "C", "Bb", "C", "A", "A"]                # one bar each
TONES = {"Dm": {5, 8, 0}, "Bb": {1, 5, 8}, "F": {8, 0, 3}, "C": {3, 7, 10}, "Eb": {6, 10, 1}, "A": {0, 4, 7}}
SCALE = {5, 6, 8, 10, 0, 1, 3, 4}           # D Eb F G A Bb C, and C# for the A chord


def hum(beat, amount=0.01):
    return beat + R.uniform(-amount, amount)


# ---- guitar ------------------------------------------------------------------------------------
def gtr(beat, shift, kind, level=0.6, muffle=0.0):
    """kind: 'c' a palm-muted chug, 'L' a short open hit, 'A' an accented chord, 'R' let ring."""
    beats, cut, rel, lv = {"c": (0.24, 0.42, 0.04, 0.75), "L": (0.45, 0.52, 0.08, 0.9),
                           "A": (0.5, 0.66, 0.1, 1.0), "R": (3.8, 0.62, 1.2, 0.85)}[kind]
    s.clip("gtr", "sample", 2, hum(beat, 0.006), beats, level=level * lv, attack=0.002, release=rel,
           pitch=shift + KEY, file=GTR, cutoff=max(0.2, cut - muffle), drive=0.35,
           pan=0.4 if R.random() < 0.5 else -0.4)


RIFF_A = [[(0, 0, "L"), (2, 0, "c"), (3, 0, "c"), (4, 0, "L"), (6, 0, "c"), (7, 0, "c"), (8, 1, "A"), (10, 0, "c"),
           (11, 0, "c"), (12, 3, "A"), (14, 0, "c"), (15, 0, "c")],
          [(0, 0, "L"), (2, 0, "c"), (3, 0, "c"), (4, 0, "L"), (6, 0, "c"), (7, 0, "c"), (8, -2, "A"), (10, -4, "A"),
           (12, -5, "A"), (14, 1, "A")]]
BREAK = [(0, 0, "A"), (3, 0, "c"), (6, 0, "L"), (8, 0, "A"), (10, 0, "c"), (11, 0, "c"), (14, 1, "A")]


def riff_a(bar0, bars, level=0.6, muffle=0.0, kicks=True, stabs=False):
    for b in range(bars):
        for st, sh, kind in RIFF_A[b % 2]:
            t = (bar0 + b) * BAR + st * ST
            gtr(t, sh, kind, level, muffle)
            if kicks and kind in ("L", "A") or (kicks and kind == "c" and st % 4 == 3):
                kit.kick(t, 0.9 if kind != "c" else 0.6)
            if stabs and kind == "A":
                strings_stab(t, sh)


def riff_b(bar0, chords, level=0.6, bars_per=2):
    """The chorus riff: a chord on the one and the three, eighth chugs between."""
    for i, name in enumerate(chords):
        sh = CH[name][2]
        for b in range(bars_per):
            t0 = (bar0 + i * bars_per + b) * BAR
            for st, kind in ((0, "A"), (4, "c"), (6, "c"), (8, "A"), (12, "c"), (14, "c")):
                gtr(t0 + st * ST, sh, kind, level)


def breakdown_riff(bar0, bars, level=0.62):
    for b in range(bars):
        for st, sh, kind in BREAK:
            if b % 4 == 3 and st == 14:
                continue                                 # a dead stop at the end of every fourth bar
            t = (bar0 + b) * BAR + st * ST
            gtr(t, sh, kind, level)
            kit.kick(t, 1.0 if kind == "A" else 0.7)


def ring(bar, name, level=0.55, beats=4):
    gtr(bar * BAR, CH[name][2], "R", level)


def bass(bar0, chords, bars_per=1, level=0.35):
    for i, name in enumerate(chords):
        root = CH[name][0] - 12 + KEY
        root = root - 12 if root > -3 else root
        s.clip("bass", "tones", 2, (bar0 + i * bars_per) * BAR, bars_per * BAR, level=level, attack=0.01,
               release=0.1, pitch=root, interval=12, timbre=1.7, cutoff=0.32, drive=0.2, **DUCK)


# ---- strings (cloud) ------------------------------------------------------------------------------
def strings(bar, bars, name, level=0.3, cut=0.6, voices=7, attack=0.3, octave=0, **kw):
    root, q, _ = CH[name]
    s.clip("strings", "cloud", 5, bar * BAR - 0.15, bars * BAR + 0.5, level=level, attack=attack, release=0.5,
           pitch=root + KEY + octave, voices=voices, gather=kw.pop("gather", 1.0), drift=0.1, timbre=1.0,
           chord=CHORD[q], cutoff=cut, reverb=0.5, **DUCK, **kw)


def strings_stab(beat, shift):
    s.clip("string_stab", "cloud", 5, beat, 0.5, level=0.3, attack=0.004, release=0.12, pitch=5 + shift + KEY,
           voices=6, gather=1.0, drift=0.05, timbre=1.0, chord=CHORD["power"], cutoff=0.7, reverb=0.3)


def section(beat, beats, pitch, level=0.4, **kw):
    """Sunfall's section: a few cloud voices on one pitch."""
    s.clip("ostinato", "cloud", 5, beat, beats, level=level, pitch=pitch + KEY, voices=4, gather=1.0, drift=0.08,
           timbre=1.6, chord=CHORD["unison"], cutoff=0.64, attack=0.006, release="full", env_curve=0.35,
           reverb=0.3, **kw)


def ostinato(bar0, bars, roots, level=0.42):
    pat = [0, 0, 12, 0, 7, 0, 12, 10]               # D D D' D A D D' C, in eighths
    for b in range(bars):
        root = roots[b % len(roots)]
        for i, off in enumerate(pat):
            section((bar0 + b) * BAR + i * 0.5, 0.45, root + off - 12, level * (1 if i % 2 == 0 else 0.75),
                    pan=0.3 if i % 2 else -0.3)


# ---- voices ------------------------------------------------------------------------------------------
CHOIR_PITCH = {5: 2.06, 1: -1.94, 8: 5.06, 3: 0.06, 6: 3.06, 0: -2.94}   # the treble choir, from C4


def choirs(bar, bars, name, level=0.36, meows=0.4):
    root, q, _ = CH[name]
    s.clip("choir", "choir", 5, bar * BAR - 0.15, bars * BAR + 0.5, level=level, attack=0.3, release=0.6,
           pitch=CHOIR_PITCH[root] + KEY, chord=CHORD[q], file=TREBLE, start=0.2, speed=0.3, voices=3,
           gather=1.0, drift=0.4, wander=2, scatter=0.4, reverb=0.6, cutoff=0.75)
    if meows:
        meow_choir(s, 4, bar * BAR - 0.15, bars * BAR + 0.5, root + 24 + KEY, CHORD[q], which="u6", level=meows,
                   voices=4, reverb=0.5)


def voice(beat, beats, pitch, level=0.5, drive=0.0):
    ring_ = 0.5
    long = beats >= 2
    s.clip("voice", "arc", 3, hum(beat), beats + ring_, level=level, attack=0.1 if long else 0.04,
           release=min(0.8, (beats + ring_) * s.spb * 0.45), pitch=pitch + KEY, ring=0.95, formant=12, burst=12,
           jitter=0.02, flicker=0.12, snap=0.1, color=0.3, drive=drive, reverb=0.45, echo=0.15, pan=0.05,
           **({"lfo_rate": 5.0, "lfo>pitch": 0.12} if long else {}))


def below(p, name, steps=1):
    """The next chord tone (or scale tone) under p, `steps` times."""
    for _ in range(steps):
        for d in range(1, 8):
            if (p - d) % 12 in TONES[name]:
                p -= d
                break
    return p


def trio(beat, beats, p, name, level=0.5):
    """Three meows: the melody, the chord tone under it, and the one under that. A cluster."""
    for q, which, pan, lv in ((p, "kitty", 0.0, 1.0), (below(p, name), "garage1", -0.35, 0.8),
                              (below(p, name, 2), "kitty", 0.35, 0.7)):   # garage8 glides ~3 st: not for a held cluster
        while q < 26:
            q += 12
        meow(s, 4, hum(beat, 0.012), beats, q + KEY, which, level=level * lv, pan=pan)


# melodies: (beat from the section's start, beats, pitch). D4=17 Eb4=18 F4=20 G4=22 A4=24 Bb4=25 C5=27
# C#5=28 D5=29 Eb5=30 E5=31 F5=32 G5=34 A5=36 Bb5=37
VERSE = [(0, 3, 17), (3, 1, 18), (4, 2, 20), (6, 2, 18), (8, 4, 17), (12, 2, 15), (14, 2, 17),
         (16, 3, 20), (19, 1, 22), (20, 2, 24), (22, 2, 25), (24, 3, 24), (27, 1, 22), (28, 4, 24)]
VERSE2 = VERSE[:10] + [(24, 2, 27), (26, 2, 25), (28, 2, 24), (30, 2, 18), (32, 0.01, 17)]
CHORUS_MEL = [(0, 2, 36), (2, 1, 34), (3, 1, 32), (4, 2, 31), (6, 2, 32),            # Dm
              (8, 3, 29), (11, 1, 32), (12, 2, 37), (14, 2, 36),                     # Bb
              (16, 2, 32), (18, 1, 34), (19, 1, 36), (20, 4, 36),                    # F
              (24, 2, 34), (26, 1, 32), (27, 1, 31), (28, 2, 34), (30, 2, 31),        # C
              (32, 2, 36), (34, 1, 34), (35, 1, 32), (36, 4, 29),                    # Dm
              (40, 2, 32), (42, 1, 34), (43, 1, 36), (44, 4, 37),                    # Bb
              (48, 3, 34), (51, 1, 32), (52, 4, 30),                                 # Eb
              (56, 3, 31), (59, 1, 28), (60, 2, 31), (62, 2, 36)]                    # A


def chorus(bar0, level=1.0):
    for i, name in enumerate(CHORUS):
        bar = bar0 + 2 * i
        strings(bar, 2, name, level=0.34, cut=0.68)
        choirs(bar, 2, name, level=0.34, meows=0.45)
        bass(bar, [name], bars_per=2)
        kit.crash(bar * BAR, 0.18 if i % 2 else 0.24)
        for b in range(2):
            t = (bar + b) * BAR
            for k in (0, 0.5, 2, 2.5, 3.5):           # the kick on the riff's chords, doubled
                kit.kick(t + k, 0.9 if k in (0, 2) else 0.7)
            kit.snare(t + 1); kit.snare(t + 3)
            for k in range(4):
                kit.ride(t + k, 1.0 if k % 2 == 0 else 0.8)
    riff_b(bar0, CHORUS, level=0.6)
    for b, ln, p in CHORUS_MEL:
        name = CHORUS[int(b // 8)]
        trio(bar0 * BAR + b, ln, p, name, level=0.5 * level)


# =======================================================================================================
INTRO, RIFF, VERSE_, PRE_, CHORUS1, BREAK_, SOLO, CHORUS2, OUTRO, END = 0, 8, 16, 32, 40, 56, 64, 72, 88, 92

# Intro: strings swell, the meow choir gathers onto D, the riff muffled, toms
strings(INTRO, 8, "Dm", level=0.32, attack=2.0, cut=0.5, cutoff_swp=0.15)
strings(INTRO + 4, 4, "Dm", level=0.18, attack=1.5, octave=12, voices=8)
meow_choir(s, 4, 0, 8 * BAR, 29, CHORD["minor"], which="u6", level=0.45, voices=5, gather=0.0, gather_swp=1.0,
           wander=12, drift=0.5, attack=2.0, reverb=0.6)
riff_a(INTRO + 4, 4, level=0.45, muffle=0.25, kicks=False)
for b in range(4, 8):
    kit.kick(b * BAR, 0.6); kit.kick(b * BAR + 2, 0.5)
for i in range(8):
    kit.tom(7 * BAR + 2 + 0.25 * i, i // 2 + 1, 0.7 + 0.04 * i)
kit.swell(RIFF * BAR, 4, 0.2)

# Riff
s.clip("impact", "sample", 5, RIFF * BAR, 6, level=0.5, release=2.0, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
kit.crash(RIFF * BAR, 0.3)
riff_a(RIFF, 8, level=0.65, stabs=True)
bass(RIFF, ["Dm"] * 8)
for b in range(8):
    t = (RIFF + b) * BAR
    kit.snare(t + 1); kit.snare(t + 3)
    for k in range(8):
        kit.hat(t + 0.5 * k, 1.0 if k % 2 == 0 else 0.6)
strings(RIFF, 8, "Dm", level=0.22, cut=0.55)
for i in range(4):
    kit.tom((RIFF + 7) * BAR + 3 + 0.25 * i, i, 0.8)

# Verse: palm-muted riff, the coil sings, the ostinato, meow choir underneath
for half, mel in ((0, VERSE), (8, VERSE2)):
    for b, ln, p in mel:
        if ln > 0.1:
            voice((VERSE_ + half) * BAR + b, ln, p, level=0.4)
riff_a(VERSE_, 16, level=0.5, muffle=0.12)
bass(VERSE_, ["Dm"] * 16)
ostinato(VERSE_ + 8, 8, [5, 5, 5, 6], level=0.36)
for b in range(16):
    t = (VERSE_ + b) * BAR
    kit.snare(t + 1, 0.85); kit.snare(t + 3, 0.9)
    for k in range(8):
        kit.hat(t + 0.5 * k, 0.9 if k % 2 == 0 else 0.55)
    if b % 4 == 0:
        meow_choir(s, 4, t - 0.15, 4 * BAR + 0.3, 29, CHORD["minor"], which="u6", level=0.3, voices=4, reverb=0.5)
strings(VERSE_, 8, "Dm", level=0.2, cut=0.55)
strings(VERSE_ + 8, 8, "Dm", level=0.24, cut=0.6)
kit.swell(PRE_ * BAR, 2, 0.18)

# Pre-chorus: let-ring chords, strings climbing, staccato meow clusters on the offbeats
for b, name in enumerate(PRE):
    bar = PRE_ + b
    ring(bar, name)
    strings(bar, 1, name, level=0.24 + 0.015 * b, cut=0.56 + 0.02 * b)
    bass(bar, [name])
    kit.kick(bar * BAR, 0.9); kit.kick(bar * BAR + 2.5, 0.7); kit.snare(bar * BAR + 2)
    for k in (1.5, 3.5):
        top = max(t for t in range(30, 38) if t % 12 in TONES[name])
        trio(bar * BAR + k, 0.5, top, name, level=0.42)
    kit.ride(bar * BAR, 0.9); kit.ride(bar * BAR + 1, 0.7); kit.ride(bar * BAR + 2, 0.9); kit.ride(bar * BAR + 3, 0.7)
for i in range(16):
    kit.tom((CHORUS1 - 1) * BAR + 0.25 * i, 5 - i // 3, 0.6 + 0.03 * i)
kit.swell(CHORUS1 * BAR, 4, 0.22)

# Chorus
s.clip("impact2", "sample", 5, CHORUS1 * BAR, 6, level=0.5, release=2.0, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
chorus(CHORUS1)

# Breakdown: half time, dead stops, tremolo strings, the meow choir
kit.crash(BREAK_ * BAR, 0.3)
breakdown_riff(BREAK_, 8)
bass(BREAK_, ["Dm"] * 8)
for b in range(8):
    t = (BREAK_ + b) * BAR
    kit.snare(t + 2, 1.0)
    if b % 4 != 3:
        kit.crash(t, 0.12, pan=-0.3)
s.clip("tremolo", "cloud", 5, BREAK_ * BAR, 8 * BAR, level=0.3, attack=0.5, release=0.8, pitch=17, voices=8,
       gather=1.0, drift=0.1, timbre=1.0, chord=CHORD["minor"], cutoff=0.66, lfo_rate=14,
       **{"lfo>amp": 0.7}, reverb=0.5)
meow_choir(s, 4, BREAK_ * BAR, 8 * BAR, 29, CHORD["minor"], which="u6", level=0.42, voices=5, gather=0.3,
           gather_swp=0.7, wander=6, drift=0.4, reverb=0.6)

# Solo: the cats shred over the chorus chords, one a bar
SOLO_CH = ["Dm", "Bb", "F", "C", "Dm", "Bb", "Eb", "A"]
kit.crash(SOLO * BAR, 0.25)
riff_b(SOLO, SOLO_CH, level=0.5, bars_per=1)
for i, name in enumerate(SOLO_CH):
    bar = SOLO + i
    t = bar * BAR
    strings(bar, 1, name, level=0.24, cut=0.62)
    bass(bar, [name])
    for k in (0, 0.5, 2, 2.5):
        kit.kick(t + k, 0.85)
    kit.snare(t + 1); kit.snare(t + 3)
    tones = sorted(p for p in range(28, 38) if p % 12 in TONES[name])
    run = tones + tones[-2:0:-1]                     # up and back down
    for k in range(16):
        p = run[(k + i) % len(run)]
        if k % 4 == 3 and i % 2:
            mew(s, 3, t + k * ST, p, k=k % 4, level=0.9, pan=0.2)
        else:
            meow(s, 3, hum(t + k * ST, 0.008), ST * 0.95, p, "kitty" if k % 2 == 0 else "garage1", level=1.0,
                 pan=-0.2 if k % 2 else 0.2)
meow(s, 3, (SOLO + 7) * BAR + 2, 2, 36, "angry", level=0.6)    # the yowl on the A at the end
for i in range(8):
    kit.tom((SOLO + 7) * BAR + 2 + 0.25 * i, i % 6, 0.8)

# Last chorus: a half step up
KEY = 1
s.clip("impact3", "sample", 5, CHORUS2 * BAR, 6, level=0.55, release=2.0, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
chorus(CHORUS2)
for b, ln, p in CHORUS_MEL:                          # the coil an octave under the cats
    voice(CHORUS2 * BAR + b, ln, p - 12, level=0.15, drive=0.2)

# Outro: the riff once more (still in Eb), the last chord held
riff_a(OUTRO, 2, level=0.65)
for b in range(2):
    t = (OUTRO + b) * BAR
    kit.snare(t + 1); kit.snare(t + 3)
t = (OUTRO + 2) * BAR
kit.crash(t, 0.35); kit.kick(t, 1.0); kit.snare(t)
gtr(t, 0, "R", 0.65)
s.clip("impact4", "sample", 5, t, 8, level=0.55, release=2.5, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
strings(OUTRO + 2, 3, "Dm", level=0.34, attack=0.05, cut=0.62)
strings(OUTRO + 2, 3, "Dm", level=0.18, attack=0.05, octave=12, voices=8)
choirs(OUTRO + 2, 3, "Dm", level=0.4, meows=0.5)
bass(OUTRO + 2, ["Dm"], bars_per=2)

if __name__ == "__main__":
    s.write()
