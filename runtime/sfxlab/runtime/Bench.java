package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** A bench: a regulator palette, or one spell's section of a family file (then name / recipe are set). */
public class Bench {
    public final ArrayList<Clip> layers = new ArrayList<>();
    public final ArrayList<Bind> binds = new ArrayList<>();
    public final ArrayList<String> notes = new ArrayList<>();
    public final ArrayList<String> comments = new ArrayList<>();   // hand-written `#` lines, kept through every save (the header line excepted)
    public String palette;            // (older signature files) the palette they were authored against; the folder says it now
    public String name;               // spell files: the display name
    public int tier; public boolean secret; public RegulatorCore.Comp[] comps;   // spell files: the recipe (null when the file has none)
    public double rtol = RegulatorCore.DEFAULT_RTOL;                // spell files: the recipe's reach tolerance
    public double root = ROOT_DEFAULT;
    public Clip byId(String id) { if (id != null) for (Clip c : layers) if (id.equals(c.id)) return c; return null; }
}
