#!/usr/bin/env python3
"""Hymn for a Dead Coil: D phrygian at 170 BPM, written as an SfxLab project by a script.

  python3 tools/music/hymn_for_a_dead_coil.py > projects/hymn_for_a_dead_coil.sfx
  python3 tools/music/hymn_for_a_dead_coil.py --riff swarm > projects/hymn_for_a_dead_coil_swarm.sfx

--riff picks the guitar (track 3); everything else is identical between them:
  pluck  the original: Karplus-Strong power chords through the drive (sounds a little 8-bit)
  twin   chopped recordings: guitar_heavy_00002 (a D power chord) hard left, guitar_heavy_00003
         (an A chord, 18 cents flat, taken up to D) hard right, re-pitched as a tape sampler would
  glitch twin, plus tape stops, 32nd-note stutters, an octave-down slam at each drop and a climb
         played on frozen grains of the ring (keep-len at speed 0)
  swarm  twin, plus a choir of the guitar_heavy_00001_pad drone gathered to power chords under
         the drops (ducked by the kick, it pumps), following the break's cadence, and the
         real bell_00003 (a G bell, taken down to D) on the four-bar downbeats
  ./sfxlab --render projects/hymn_for_a_dead_coil.sfx renders/hymn.ogg

Tracks as the GUI numbers them: 1 kick, 2 perc, 3 riff, 4 coil lead, 5 choir, 6 fx.
The ideas worth stealing:
  - the lead is an `arc` clip (a singing Tesla coil). ring 0.62 turns each crack into a short
    sine at `formant` semitones above the pitch, so the buzz takes on a voice
  - the hi-hats are an arc firing at exactly the sixteenth rate (divide=4, pitch four octaves
    above it) with `misfire` dropping ticks at random; keyed=0 so a project key can't detune the tempo
  - the choir is vocals/ethereal_vocals.wav (E4, so pitch -2 for D) with gather swept 0 -> 1:
    the voices start scattered and lock into the chord; the ending sweeps it back
Times are in beats; clip() converts them to seconds. Release fades inside a clip's length.
Unknown param keys are dropped silently by the loader, so this script checks every key
against the PSpec names in runtime/sfxlab/runtime/Sfx.java and fails on a typo.
"""
import random, math, re, sys, pathlib, argparse
_ap = argparse.ArgumentParser(); _ap.add_argument("--riff", default="pluck", choices=["pluck", "twin", "glitch", "swarm"])
MODE = _ap.parse_args().riff
VR = random.Random(4242)   # seeds for clips only the variants add, so the shared layers keep theirs
_SFX = pathlib.Path(__file__).resolve().parents[2] / "runtime/sfxlab/runtime/Sfx.java"
VALID = {n.replace(" ", "_") for n in re.findall(r'new PSpec\("([^"]+)"', _SFX.read_text())} | {"file", "keyed"}
random.seed(170)
BPM = 170; B = 60 / BPM; S = 0.25          # S = a sixteenth, in beats
out = ["# SfxLab project v2: clip name type track start dur seed key=value...",
       "# 'Hymn for a Dead Coil' - D phrygian, 170 BPM, by Claude. tracks (GUI numbering): 1 kick, 2 perc, 3 riff, 4 coil lead, 5 choir, 6 fx",
       "loop 0", "root 110.0000"]
for i, v in enumerate([1.0, 0.7, 0.72, 0.45, 0.85, 0.7]): out.append(f"track {i} {v:.3f} 0")
def clip(name, typ, tr, t0, t1, _burn=False, _vr=False, **kw):
    if _burn: random.getrandbits(63); return   # a pluck clip a variant replaces: spend its seed anyway
    bad = set(kw) - VALID
    if bad: sys.exit(f"unknown param key(s) on {name}: {sorted(bad)}")
    s = " ".join(f"{k}={round(v,4) if isinstance(v,float) else v}" for k, v in kw.items())
    out.append(f"clip {name.replace(' ','_')} {typ} {tr} {max(0,t0)*B:.4f} {(t1-t0)*B:.4f} {(VR if _vr else random).getrandbits(63)} {s}")
TONES, NOISE, PLUCK, SAMPLE, CHOIR, ARC = 1, 2, 4, 5, 6, 8
bar = lambda n: n * 4
# sections (bars): intro 0-3, drop A 4-11, break 12-15, drop B 16-23, end 24
DUCK = dict(duck=0.55, duck_from=1)        # sidechain from the kick track

# ---------------- drums ----------------
def kick(t, lv=1.0):
    clip("kick", TONES, 0, t, t + 0.75, level=1.0*lv, attack=0.001, release=0.6, pitch=3, pitch_swp=-22,
         interval=0, timbre=0.0, drive=0.7, env_curve=0.3, keyed=0)
    clip("kick_body", TONES, 0, t, t + 0.55, level=0.7*lv, attack=0.002, release=0.45, pitch=-7, pitch_swp=-4,
         interval=0, timbre=0.0, drive=0.5, env_curve=0.2, keyed=0)
    clip("click", NOISE, 0, t, t + 0.04, level=0.3*lv, attack=0.0, release=0.012, cutoff=0.85, filter=2, keyed=0)
def snare(t, lv=1.0, up=0):
    clip("snare", NOISE, 1, t, t + 0.55, level=0.55*lv, attack=0.001, release=0.5, cutoff=0.7 + up*0.01, filter=1,
         resonance=0.15, color=0.3, env_curve=0.55, reverb=0.22, pan=0.05, keyed=0)
    clip("snare body", TONES, 1, t, t + 0.3, level=0.4*lv, attack=0.001, release=0.25, pitch=8 + up, pitch_swp=-7,
         interval=7, timbre=0.8, drive=0.3, env_curve=0.5, keyed=0)
    if lv > 0.6:   # an engine stroke under every backbeat: the industrial grain
        clip("stroke", SAMPLE, 1, t, t + 0.7, level=0.45*lv, attack=0.0, release=0.3, pitch=-2, drive=0.4,
             cutoff=0.8, file="machines/steam_engine_stroke_3.ogg", keyed=0)
def roll(t0, t1, steps=S):   # snare roll, rising in level and pitch
    n = int(round((t1 - t0) / steps))
    for i in range(n): snare(t0 + i*steps, 0.35 + 0.65*i/n, up=int(6*i/n))
# breakbeat, per bar: (kicks, snares, ghosts) in sixteenths
BREAK_A = [([0, 6, 10], [4, 12], [7, 9, 15]), ([0, 2, 10, 11], [4, 12], [9, 14])]
BREAK_B = [([0, 3, 6, 10], [4, 12], [7, 9, 15]), ([0, 2, 6, 10, 11, 14], [4, 12], [9, 13])]
def drums(b0, b1, pat, fill_last=True):
    for b in range(b0, b1):
        ks, ss, gs = pat[(b - b0) % 2]
        last = fill_last and b == b1 - 1
        for k in ks:
            if not (last and k >= 8): kick(bar(b) + k*S)
        for s in ss:
            if not (last and s >= 8): snare(bar(b) + s*S)
        for g in gs:
            if not (last and g >= 8): snare(bar(b) + g*S, 0.3)
        if last: kick(bar(b) + 8*S); roll(bar(b) + 8*S, bar(b + 1))
# electric hats: an arc firing at exactly the sixteenth rate (divide 4: pitch 4 octaves above the rate);
# misfire drops ticks at random, so the pattern plays itself
HAT_PITCH = 12 * math.log2(BPM / 60 * 4 * 16 / 110)
def hats(t0, t1, misfire, lv):
    clip("coil hats", ARC, 1, t0, t1, level=lv, attack=0.0, release=0.05, pitch=HAT_PITCH, divide=4, burst=1.4,
         snap=0.85, misfire=misfire, filter=2, cutoff=0.72, pan=-0.25, keyed=0)

# ---------------- riff: palm-muted power chords, double-tracked ----------------
D2, Eb2, F2, G2, C2, Bb1, A1 = -7, -6, -4, -2, -9, -11, -12
RIFF = [(0,D2,1),(1,D2,1),(3,D2,1),(5,D2,1),(6,Eb2,2),(8,D2,1),(10,D2,1),(11,D2,1),(12,F2,2),(14,Eb2,2),
        (16,D2,1),(17,D2,1),(19,D2,1),(21,D2,1),(22,C2,2),(24,Bb1,2),(26,C2,2),(28,Eb2,1),(29,D2,1),(30,D2,2)]
CLIMB = [(i, p, 1) for i, p in enumerate([D2]*4 + [Eb2]*4 + [F2]*4 + [G2]*4 + [A1+12]*8 + [Bb1+12]*4 + [C2+12]*4)]
G_L, G_R, G_PAD = "resonant/guitar_heavy_00002.ogg", "resonant/guitar_heavy_00003.ogg", "resonant/guitar_heavy_00001_pad.ogg"
def gtr(t, beats, off, side, accent=False, lv=0.85, **kw):
    """One chug from the recordings: off = semitones above D2. The right guitar is an A chord 18 cents
    flat: up a fourth to D, or down a fifth (an octave under the left) once the riff climbs high."""
    f, sh = (G_L, 0.0) if side < 0 else (G_R, 5.18 if off <= 3 else -6.82)
    a = dict(level=lv, attack=0.0, release=0.08 if accent else 0.03, pitch=off + sh, cutoff=0.95 if accent else 0.72,
             resonance=0.1, pan=0.5*side, file=f, **DUCK); a.update(kw)
    clip("gtr", SAMPLE, 2, t + (0.010/B if side > 0 else 0), t + beats, _vr=True, **a)
def variant_bars(rep, b0, pat):
    """The glitch tricks on a two-bar pass: returns the steps they take over."""
    if MODE != "glitch" or pat is CLIMB: return set()
    t = bar(rep)
    if (rep - b0) % 4 == 2:   # second pass of four bars: the last beat is a tape stop
        for side in (-1, 1): gtr(t + 28*S, 1.0, 0, side, accent=True, pitch_swp=-30, release=0.05)
    else:                     # first pass: the last beat stutters in 32nds, swelling
        for i in range(8):
            gtr(t + 28*S + i*S/2, S/2 * 0.9, 0, (-1, 1)[i % 2], lv=0.45 + 0.06*i, release=0.01, cutoff=0.6 + 0.04*i)
    if rep == b0:             # the drop lands an octave down, at half speed
        for side in (-1, 1): gtr(t, 1.0, -12, side, accent=True, lv=0.9, release=0.2)
    return set(range(28, 32))
def riff(b0, b1, last_climb=False):
    for rep in range(b0, b1, 2):
        pat = CLIMB if (last_climb and rep == b1 - 2) else RIFF
        taken = variant_bars(rep, b0, pat)
        for st, p, ln in pat:
            t = bar(rep) + st*S
            for side in (-1, 1):
                if MODE == "pluck":
                    clip("chug", PLUCK, 2, t + (0.012/B if side > 0 else 0), t + ln*S + 0.08, level=0.62, attack=0.001,
                         release=0.06, pitch=p, interval=7, damp=0.55, sustain=0.55, pick=0.9, drive=0.8, cutoff=0.6,
                         resonance=0.2, pan=0.45*side, **DUCK)
                    continue
                clip("chug", PLUCK, 2, 0, 0, _burn=True)
                if st in taken: continue
                if MODE == "glitch" and pat is CLIMB:   # the climb on frozen grains of the ring: a buzzsaw
                    gtr(t, ln*S*0.8, p - D2, side, lv=0.7, pitch_mode=1, speed=0.0, start=0.12, release=0.01)
                else:
                    gtr(t, ln*S + (0.3 if ln >= 2 else 0.15), p - D2, side, accent=ln >= 2)
            if ln >= 2 or st % 8 == 0:
                clip("sub", TONES, 2, t, t + ln*S, level=0.35, attack=0.005, release=0.06, pitch=p - 12, interval=0,
                     timbre=0.0, **DUCK)

# ---------------- coil lead: the arc as a singing voice ----------------
SCALE = [5, 6, 8, 10, 0, 1, 3]   # D phrygian as pitch classes from A
def third_below(p):
    i = SCALE.index(p % 12); return -((p % 12 - SCALE[(i - 2) % 7]) % 12)
D4, Eb4, F4, G4, A4, Bb4, C5, D5, Eb5, F5 = 17, 18, 20, 22, 24, 25, 27, 29, 30, 32
LEAD = [(0,2,D5),(2,1,A4),(3,1,Bb4), (4,1.5,C5),(5.5,.5,Bb4),(6,2,A4), (8,1,G4),(9,1,A4),(10,1,Bb4),(11,1,C5),
        (12,3,D5),(15,1,Eb5), (16,2,D5),(18,1,F5),(19,1,Eb5), (20,1.5,D5),(21.5,.5,C5),(22,2,Bb4),
        (24,1,A4),(25,.5,Bb4),(25.5,.5,A4),(26,1,G4),(27,1,F4), (28,4,A4)]
def coil(t0, t1, p, lv=0.5, harm=False, pan=0.1):
    clip("coil", ARC, 3, t0, t1, level=lv, attack=0.012, release=min(0.25, (t1-t0)*B*0.4), pitch=p, ring=0.62,
         formant=12, burst=7, jitter=0.05, snap=0.4, flicker=0.25, drive=0.3, echo=0.18, reverb=0.3,
         lfo_rate=5.5, pan=pan)   # for vibrato add **{"lfo>pitch": 0.22}: the heard take had none
def lead(b0, harmonise=False):
    for st, ln, p in LEAD:
        t = bar(b0) + st
        coil(t, t + ln, p)
        if harmonise: coil(t + 0.03, t + ln, p + third_below(p), lv=0.36, pan=-0.3)

# ---------------- choir: a recording on E4, taken down to D, voices gathered to a chord ----------------
def choir(t0, t1, shift, chord, lv, gather=1.0, gswp=0.0, wander=3, att=0.3, rel=0.8, start=0.2, duck=True, **kw):
    clip("choir", CHOIR, 4, t0, t1, level=lv, attack=att, release=rel, pitch=-2 + shift, chord=chord,
         file="vocals/ethereal_vocals.wav", start=start, speed=0.35, voices=10, gather=gather, gather_swp=gswp,
         drift=0.5, wander=wander, scatter=0.35, reverb=0.55, cutoff=0.85, **(DUCK if duck else {}), **kw)
MINOR, MAJOR, POWER = 5, 2, 1

# ================= the arrangement =================
# INTRO (bars 0-3): the choir grows out of chaos into D minor; a D drone; the hats wake up sputtering
choir(0, bar(4) + 1, 0, MINOR, 0.65, gather=0.0, gswp=1.0, wander=12, att=3.0, rel=1.2, duck=False)
clip("drone", SAMPLE, 4, 0, bar(4), level=0.5, attack=2.5, release=0.6, pitch=0, cutoff=0.5, loop=1,
     pitch_mode=1, speed=0.6, file="resonant/guitar_heavy_00001_pad.ogg", reverb=0.4)
hats(bar(1), bar(2), misfire=0.8, lv=0.25); hats(bar(2), bar(3), misfire=0.55, lv=0.3); hats(bar(3), bar(4), misfire=0.2, lv=0.34)
kick(bar(2)); kick(bar(3)); kick(bar(3) + 2.5)
roll(bar(3) + 2, bar(4), S/2 * 2)
# the coil winds up: a buzz sweeping two octaves into the drop
clip("coil riser", ARC, 3, bar(2), bar(4), level=0.35, attack=2.0, release=0.05, pitch=5, pitch_swp=24, ring=0.3,
     formant=19, burst=4, jitter=0.15, misfire=0.1, snap=0.5, flicker=0.4, reverb=0.3)
clip("riser", SAMPLE, 5, bar(4) - 2.28/B, bar(4) + 2, level=0.55, release=0.4, file="gravitic/gravitic_riser_00001.ogg")

# DROP A (bars 4-11)
for b in (4, 16): clip("impact", SAMPLE, 5, bar(b), bar(b) + 8, level=0.7, release=2, file="gravitic/gravitic_impact_00001.ogg")
drums(4, 12, BREAK_A)
hats(bar(4), bar(12), misfire=0.3, lv=0.34)
riff(4, 12)
lead(4)
def swarm(t0, t1, shift, lv, gswp=0.0, wander=1, att=0.15, rel=0.5, duck=True):
    clip("gtr_swarm", CHOIR, 2, t0, t1, _vr=True, level=lv, attack=att, release=rel, pitch=shift, chord=POWER,
         file=G_PAD, start=0.1, speed=0.5, voices=6, gather=1.0, gather_swp=gswp, drift=0.3, wander=wander,
         scatter=0.3, cutoff=0.7, reverb=0.2, **(DUCK if duck else {}))
def bell(t, lv=0.5):
    clip("bell_real", SAMPLE, 5, t, t + 16, _vr=True, level=lv, release=4, pitch=-5.37, file="resonant/bell_00003.ogg", reverb=0.45)
if MODE == "swarm":
    swarm(bar(4), bar(12), 0, 0.35)
    for b in (4, 8): bell(bar(b))
choir(bar(4), bar(12), 0, MINOR, 0.45, wander=2)

# BREAK (bars 12-15): the machine keeps time, the choir sings the phrygian cadence Dm Bb C Eb -> Dm
for b, (sh, ch) in enumerate([(0, MINOR), (-4, MAJOR), (-2, MAJOR), (1, MAJOR)]):
    choir(bar(12 + b), bar(13 + b) + 0.6, sh, ch, 0.6, att=0.25, rel=0.9, duck=False, wander=2)
    root = [17, 13, 15, 18][b]   # D4 Bb3 C4 Eb4
    clip("bell", TONES, 5, bar(12 + b), bar(12 + b) + 7, level=0.45, attack=0.002, release=5, pitch=root + 12,
         interval=12, timbre=0.0, bank=1.0, stretch=0.08, tilt=-0.3, fm_ratio=3.5, fm_depth=1.2,
         env_curve=0.7, reverb=0.6, pan=-0.2 + 0.13*b)
    clip("bass", TONES, 2, bar(12 + b), bar(13 + b), level=0.4, attack=0.3, release=0.4, pitch=[D2,Bb1,C2,Eb2][b] - 12,
         interval=12, timbre=0.4, cutoff=0.4)
    for q in range(4):   # an engine on every beat
        f = f"machines/steam_engine_stroke_{[3,5,3,7][q]}.ogg"
        clip("engine", SAMPLE, 1, bar(12 + b) + q, bar(12 + b) + q + 0.9, level=0.5 if q % 2 == 0 else 0.35,
             release=0.2, pitch=-3, file=f, pan=0.3 if q % 2 else -0.3, reverb=0.2, keyed=0)
    kick(bar(12 + b), 0.8)
    if MODE == "swarm": swarm(bar(12 + b), bar(13 + b) + 0.3, [0, -4, -2, 1][b], 0.4, duck=False)
HYMN = [(0,2,A4),(2,2,D5),(4,2,F4),(6,2,Bb4),(8,2,C5),(10,2,G4),(12,2,Bb4),(14,1,G4),(15,1,Eb5)]
for st, ln, p in HYMN: coil(bar(12) + st, bar(12) + st + ln, p, lv=0.42)
hats(bar(14), bar(15), misfire=0.5, lv=0.28); hats(bar(15), bar(16), misfire=0.1, lv=0.34)
roll(bar(15), bar(16), S)
clip("coil riser", ARC, 3, bar(15), bar(16), level=0.35, attack=1.0, release=0.05, pitch=10, pitch_swp=19, ring=0.3,
     formant=19, burst=4, jitter=0.15, snap=0.5, flicker=0.4, reverb=0.3)
clip("shatter", SAMPLE, 5, bar(16), bar(16) + 6, level=0.6, release=1.5, pitch=-2, file="resonant/crescendo_shatter.ogg", reverb=0.3)

# DROP B (bars 16-23): busier break, harmonised coil, the choir an octave up, orchestral stabs
drums(16, 24, BREAK_B)
hats(bar(16), bar(24), misfire=0.15, lv=0.36)
for b in range(16, 24):
    for st in (2, 6, 10, 14):
        clip("open hat", NOISE, 1, bar(b) + st*S, bar(b) + st*S + 0.5, level=0.16, attack=0.001, release=0.4,
             cutoff=0.9, filter=2, color=0.0, env_curve=0.4, pan=0.3, keyed=0)
riff(16, 24, last_climb=True)
lead(16, harmonise=True)
choir(bar(16), bar(24), 12, MINOR, 0.4, wander=2)
choir(bar(16), bar(24), 0, POWER, 0.25, wander=1)
if MODE == "swarm":
    swarm(bar(16), bar(24), 0, 0.35)
    for b in (16, 20): bell(bar(b))
for b in (16, 20):
    clip("stab", SAMPLE, 5, bar(b), bar(b) + 6, level=0.5, release=1.5, pitch=-6, file="resonant/sforzando.ogg", reverb=0.3)

# END (bar 24): one last chord, and the coil dies
kick(bar(24)); snare(bar(24))
for side in (-1, 1):
    if MODE == "pluck":
        clip("last chord", PLUCK, 2, bar(24), bar(24) + 10, level=0.6, attack=0.001, release=3, pitch=D2, interval=7,
             damp=0.4, sustain=0.95, pick=0.9, drive=0.8, cutoff=0.7, pan=0.45*side)
    else:
        clip("last chord", PLUCK, 2, 0, 0, _burn=True)
        gtr(bar(24), 10, 0, side, accent=True, lv=0.9, release=2.5)
if MODE == "swarm":
    swarm(bar(24), bar(24) + 10, 0, 0.45, gswp=-1.0, wander=12, att=0.02, rel=4, duck=False)
    bell(bar(24), 0.6)
clip("sub", TONES, 2, bar(24), bar(24) + 8, level=0.4, attack=0.005, release=3, pitch=D2 - 12, interval=0, timbre=0.0)
choir(bar(24), bar(24) + 10, 0, MINOR, 0.6, att=0.02, rel=4, duck=False, gather=1.0, gswp=-1.0, wander=12)
clip("crush", SAMPLE, 5, bar(24) - 0.31/B, bar(24) + 10, level=0.6, release=2, file="impacts/heavy-metal-crush.wav", reverb=0.3)
clip("dying coil", ARC, 3, bar(24), bar(24) + 10, level=0.4, attack=0.01, release=4, pitch=29, pitch_swp=-36, ring=0.5,
     formant=12, burst=6, jitter=0.4, misfire=0.5, flicker=0.8, reverb=0.5, echo=0.3)
print("\n".join(out))
