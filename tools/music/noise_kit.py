"""A drum kit made only of noise clips (2026-11-10: the arc ticker hats and the tones kicks, snare bodies
and toms sounded off; noise sounded right).

    from noise_kit import Kit
    kit = Kit(song, kick_track=0, track=1)
    kit.kick(beat); kit.snare(beat); kit.hat(beat); kit.open_hat(beat); kit.tom(beat, n); kit.crash(beat)

The kick is Bubby's recipe: noise through the low-pass, cutoff 0.7 swept by -1 over the clip, so it closes
from ~2.1 kHz to nothing in the first 70% of the hit. Everything else is noise through one filter.
Clip lengths are in beats; every hit decays over its whole clip (release="full").
"""
from sfxsong import cutoff_for_hz


class Kit:
    def __init__(self, song, kick_track=0, track=1, kick_beats=None, kick_color=0.5, kick_res=0.0, kick_drive=0.0):
        self.s, self.kt, self.t = song, kick_track, track
        self.kick_beats = kick_beats or 0.30 / song.spb * 1.0      # ~0.3 s
        self.kick_color, self.kick_res, self.kick_drive = kick_color, kick_res, kick_drive

    def kick(self, beat, vel=1.0, **kw):
        self.s.clip("kick", "noise", self.kt, beat, kw.pop("beats", self.kick_beats), level=min(1.0, vel),
                    attack=0.001, release="full", filter=0, cutoff=0.7, cutoff_swp=-1.0,
                    resonance=self.kick_res, color=self.kick_color, drive=self.kick_drive,
                    env_curve=kw.pop("env_curve", 0.2), keyed=0, **kw)

    def snare(self, beat, vel=1.0, verb=0.25, body=True):
        """The crack (band-passed noise, as in Nine Lives) and, instead of a tones body, a short dark
        noise thump closing like a small kick."""
        self.s.clip("snare", "noise", self.t, beat, 0.5, level=min(1.0, 0.85 * vel), attack=0.001,
                    release="full", filter=1, cutoff=0.74, resonance=0.2, color=0.3, env_curve=0.6,
                    reverb=verb, keyed=0)
        if body:
            self.s.clip("snare_body", "noise", self.t, beat, 0.25, level=min(1.0, 0.7 * vel), attack=0.001,
                        release="full", filter=0, cutoff=0.55, cutoff_swp=-0.45, resonance=0.35, color=0.6,
                        env_curve=0.4, keyed=0)

    def hat(self, beat, vel=1.0, pan=-0.25):
        self.s.clip("hat", "noise", self.t, beat, 0.12, level=min(1.0, 0.32 * vel), attack=0.001,
                    release="full", filter=2, cutoff=0.88, color=0.0, env_curve=0.7, pan=pan, keyed=0)

    def open_hat(self, beat, vel=1.0, pan=0.3):
        self.s.clip("open_hat", "noise", self.t, beat, 0.45, level=min(1.0, 0.22 * vel), attack=0.002,
                    release="full", filter=2, cutoff=0.86, color=0.0, env_curve=0.4, pan=pan, keyed=0)

    def ride(self, beat, vel=1.0, pan=0.35):
        self.s.clip("ride", "noise", self.t, beat, 1.0, level=min(1.0, 0.2 * vel), attack=0.001,
                    release="full", filter=1, cutoff=0.9, resonance=0.55, color=0.0, env_curve=0.5,
                    reverb=0.2, pan=pan, keyed=0)

    TOMS = [260, 200, 160, 125, 100, 80]      # Hz, high to floor

    def tom(self, beat, n=0, vel=1.0):
        """A resonant band-pass on noise, gliding down onto the tom's note: the ring is the filter's.
        (A low-pass let the rumble under the cutoff drown the ring.) Plus a short thump under it."""
        f = self.TOMS[n % len(self.TOMS)]
        self.s.clip("tom", "noise", self.t, beat, 0.9, level=min(1.0, 0.9 * vel), attack=0.001,
                    release="full", filter=1, cutoff=cutoff_for_hz(f * 1.15), cutoff_swp=-0.045,
                    resonance=0.88, color=0.3, env_curve=0.45, reverb=0.3, pan=0.35 - 0.14 * (n % 6), keyed=0)
        self.s.clip("tom_thump", "noise", self.t, beat, 0.2, level=min(1.0, 0.35 * vel), attack=0.001,
                    release="full", filter=0, cutoff=cutoff_for_hz(f * 2.5), cutoff_swp=-0.4, color=0.7,
                    env_curve=0.4, pan=0.35 - 0.14 * (n % 6), keyed=0)

    def crash(self, beat, level=0.25, pan=0.2):
        self.s.clip("crash", "noise", self.t, beat, 8, level=level, attack=0.001, release="full", filter=2,
                    cutoff=0.8, color=0.0, env_curve=0.75, reverb=0.45, pan=pan, keyed=0)

    def swell(self, end_beat, beats, level=0.2):
        """A reversed-cymbal swell landing on end_beat."""
        self.s.clip("swell", "noise", self.t, end_beat - beats, beats, level=level,
                    attack=min(2.0, beats * self.s.spb * 0.95), release=0.02, filter=2, cutoff=0.7,
                    cutoff_swp=0.15, color=0.0, reverb=0.3, keyed=0)
