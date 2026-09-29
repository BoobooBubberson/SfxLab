package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** A spell of the loaded family: its signature (a bench file) and, when the file carries one, its recipe. */
public class Spell { public String id, name; public RegulatorCore.Recipe recipe; public Bench bench; public volatile double w; }
