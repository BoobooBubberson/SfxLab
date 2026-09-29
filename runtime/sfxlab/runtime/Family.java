package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** A family file: the palette, then each spell as a `spell <id>` section (name, recipe, layers, binds, notes). */
public record Family(Bench palette, java.util.List<Spell> spells) {}
