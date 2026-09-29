package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** One tracked sinusoid of a partials analysis. */
public class PTrack { public int start, len; public float[] freq, amp; public float fmed, ratio; public int harm; }   // start = first frame - 1: one fade frame each end; ratio = median freq / f0, harm = harmonic number (0 = inharmonic)
