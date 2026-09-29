package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

// =========================================================================
// RegulatorCore: the Harmonic Regulator's machine, with no Swing and no
// Minecraft in it. Crank physics, the lever state machine, figure sampling,
// recipe matching with shape equivalence, the research setpoint / copy
// socket, and the signal contract (docs/HARMONIC-REGULATOR.md §3–4). Every
// constant is the web prototype's (docs/regulator-prototype.html), which is
// the oracle: when a port behaves differently, the prototype is right.
//
// The mod uses this class through the shared runtime (sfxlab.runtime), the
// same source SfxLab's machine window drives.
//
// Use: construct, setTarget(recipe), power(true); feed input (selectArm,
// axisLever, nudge / drag*, latch, phaseStep, setReach); call tick(dt) each
// frame; read signals[] (SIGNALS names), eval, targetEval, and drain
// events(). figurePoint / armVector / blueprint draw the ribbon and the
// pinned sigil.
//
// The game's machine pins nothing: setFree(arms, motionsPerArm) instead of
// setTarget, then the same input and tick; read matched (the recipe held
// exactly, or null) and voice() writes it (lastVoiced says which).
// =========================================================================
public class RegulatorCore {
    // ---- tuning constants (prototype-exact)
    public static final int ARMS = 3, AXES = 3, MAX_N = 8;
    public static final double MAX_VEL = 4;            // crank rev/s; ratio = 2·|vel|, so ratio 8 at most
    public static final double CATCH_W = 0.16;         // catch half-width at integer n is CATCH_W / n
    public static final double REST_R = 0.12;          // below this ratio the crank settles to rest
    public static final double CATCH_RATE = 7, REST_RATE = 9, FRICTION = 0.09;
    public static final double BRAKE = 0.16;           // extra linear brake below ratio 1, in vel units (0.32 ratio/s)
    public static final double SLIP_NUDGE = 0.5, SLIP_DRAG = 0.15, DRAG_SMOOTH = 0.35;
    public static final double NUDGE_WHEEL = 0.05, NUDGE_FINE = 0.01, NUDGE_BUTTON = 0.125;   // vel steps: ratio ±0.1, ±0.02, ±0.25
    // brake-and-wells model (wells = true), in ratio units, none of it scaling with the ratio: a high resonance is a
    // harder figure to read, not a heavier crank. No friction: the crank keeps the speed it is left at, a detuned crank
    // stays detuned and readable. The wheel is the only way up, the brake the only way down.
    public static final double BRAKE_RATE = 0.4;    // ratio/s at a tap; a hold bites harder (BRAKE_BITE × after BRAKE_RAMP s) for a long descent
    public static final double BRAKE_BITE = 4, BRAKE_RAMP = 1.2;
    public static final double WELL_TAU = 0.6;      // s: released inside the acceptance window, the crystal eases the crank onto the integer at this time constant
    public static final double NOTCH_JITTER = 0.3;  // a wheel notch varies by ±30 %: counting notches is no substitute for watching the figure
    public static final double ENGAGE_AMP = 0.04, ENGAGE_R = 0.05;
    public static final double SETPOINT_JITTER = 0.2, SOCKET_JITTER = 0.035;
    public static final double DEFAULT_REACH = 1.0;   // a pulled lever starts at full reach: recipes target 1 unless they say otherwise
    public static final int[][] TIERS = {{2, 1}, {3, 2}, {3, 3}};   // tier 1..3 -> {arms, motions per arm}
    public static final String[] AXIS = {"X", "Y", "Z"};
    public static final String[] PHASE = {"0", "¼", "½", "¾"};

    /** The signal contract, in the order of signals[]. arm{n}.pitch is derived (pitch(arm)). tone.* (§4): the reach
     *  on each chord tone of the harmonic series, from every engaged motion whichever arm holds it, weighted by how
     *  close the motion's folded pitch is to that tone: a ratio gliding 1 → 2 sings root, third, fifth, seventh, root. */
    public static final String[] SIGNALS = {"arm1.ratio", "arm2.ratio", "arm3.ratio", "arm1.reach", "arm2.reach", "arm3.reach",
                                     "radiance", "consonance", "tension", "drive", "coherence", "score",
                                     "orb.speed", "orb.accel", "orb.curl", "orb.radius", "stir",
                                     "tone.root", "tone.third", "tone.fifth", "tone.seventh", "stack", "fit"};
    public static final int S_RATIO = 0, S_REACH = 3, S_RADIANCE = 6, S_CONSONANCE = 7, S_TENSION = 8, S_DRIVE = 9, S_COHERENCE = 10, S_SCORE = 11,
                     S_ORB_SPEED = 12, S_ORB_ACCEL = 13, S_ORB_CURL = 14, S_ORB_RADIUS = 15, S_STIR = 16, S_TONE = 17, S_STACK = 21, S_FIT = 22;
    /** The chord tones' pitch classes in semitones (just ratios 1, 5/4, 3/2, 7/4) and how sharply a motion's pitch must sit on one.
     *  Each tone signal sums √reach over the motions on that pitch class, so reach is heard but compressed. */
    public static final double[] TONE_ST = {0, 3.8631, 7.0196, 9.6883};
    public static final double TONE_K = 2.0;   // weight e^(−TONE_K·semitones off): half a semitone 0.37, one 0.14, two 0.02
    public static final double STIR_SMOOTH = 0.15;   // s: how fast `stir` follows the arms starting or stopping
    public static final double ORB_SMOOTH = 0.06;   // s: the orb signals' envelope follows the pen with this lag

    // ---- recipes
    /** One motion of a recipe: axis (0 X, 1 Y, 2 Z), integer ratio, phase in quarter cycles, and the reach target
     *  (amp): the blueprint is drawn with it and a match needs the motion's reach within the recipe's rtol of it. */
    public record Comp(int axis, int n, int phase, double amp) {}
    public static final double DEFAULT_RTOL = 0.1;
    public static final class Recipe {
        public final String id, name, reward; public final int tier; public final boolean secret; public final Comp[] comps;
        public double rtol = DEFAULT_RTOL;   // reach tolerance: |reach − amp| ≤ rtol counts
        private java.util.List<Comp[]> variants;
        public Recipe(String id, String name, int tier, String reward, boolean secret, Comp... comps) {
            this.id = id; this.name = name; this.tier = tier; this.reward = reward; this.secret = secret; this.comps = comps;
        }
        public int arms() { return TIERS[tier - 1][0]; }
        public int motionsPerArm() { return TIERS[tier - 1][1]; }
        /** Phase sets that trace the identical figure: start a quarter cycle later (each ×n motion gains n
         *  quarters) and / or run it backwards (p becomes 2 − p). Mirroring one axis alone is NOT here, so a
         *  mirrored asymmetric sigil is a wrong answer; a symmetric sigil's mirror is already in the set. */
        public java.util.List<Comp[]> variants() {
            if (variants != null) return variants;
            java.util.List<Comp[]> out = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int rev = 0; rev < 2; rev++)
                for (int k = 0; k < 4; k++) {
                    Comp[] cs = new Comp[comps.length];
                    StringBuilder key = new StringBuilder();
                    for (int i = 0; i < comps.length; i++) {
                        Comp c = comps[i];
                        int p = ((((rev == 1 ? 2 - c.phase : c.phase) + c.n * k) % 4) + 4) % 4;
                        cs[i] = new Comp(c.axis, c.n, p, c.amp);
                        key.append(p).append(',');
                    }
                    if (seen.add(key.toString())) out.add(cs);
                }
            return variants = out;
        }
    }
    /** The prototype's roster. A mod supplies its own list to the constructor. */
    public static final Recipe[] DEFAULT_RECIPES = {
        new Recipe("firebolt", "Fire bolt", 1, "fire", false, new Comp(0, 3, 1, 1), new Comp(1, 2, 0, 1)),
        new Recipe("cinder", "Cinder bloom", 2, "lava", false, new Comp(0, 1, 1, 1), new Comp(1, 1, 0, 1),
                   new Comp(0, 5, 1, 0.35), new Comp(1, 5, 2, 0.35), new Comp(2, 3, 0, 0.55)),
        new Recipe("lance", "Torch lance", 3, "torch", false, new Comp(0, 1, 0, 1), new Comp(1, 2, 1, 0.8), new Comp(2, 3, 1, 0.7),
                   new Comp(0, 4, 2, 0.4), new Comp(1, 5, 0, 0.3), new Comp(2, 6, 3, 0.25)),
        new Recipe("wisp", "Will-o'-wisp", 1, "cloud", true, new Comp(0, 1, 0, 1), new Comp(1, 2, 0, 1)),
    };

    // ---- machine state
    /** One arm × axis motion. off: !eng. driven: eng && drv (follows the crank and trim). held: eng && !drv. */
    public static final class Motion { public boolean eng, drv, act; public double r = 1, amp = DEFAULT_REACH; public int ph; public double osc; }   // act: the lever is down (coupled-lever model)   // osc: the motion's own accumulated angle (radians), so a changing ratio bends the trace instead of jumping it
    /** A saved motion (voiced crystals, the copy socket). */
    public record Snap(int arm, int axis, double r, int phase, double amp) {}
    public static final class Eval { public double score; public boolean exact; public double fit; }   // fit: reach agreement of the matched components, e^(−6·|reach − target|) averaged over the recipe

    public final Recipe[] recipes;
    public final Motion[][] comps = new Motion[ARMS][AXES];
    public final int[] focus = {-1, -1, -1};   // per arm: the axis the trim controls act on (the last lever pressed there)
    public Recipe target;
    public int arm;                       // the selected arm the axis levers act on
    public boolean powered;
    public double vel, ang, slip;         // crank: rev/s, degrees, seconds of slip left
    public int caught = -1;               // -1 free, 0 at rest; classic crank: n = caught at integer n
    public boolean drag;
    public double tau;                    // machine time in seconds (drives the pen)
    /** classic: the prototype's crank, which catches and holds at integer ratios. Default is the free crank: friction
     *  only, and the crystal accepts a motion when it is latched within snapTol/√n of integer n, snapping it there. */
    public boolean classic;
    /** coupling (default): a lever down makes its motion ACTIVE — the trim controls act on every active motion,
     *  and touching the crank couples it to all of them (at the slowest one already turning). Latch snaps every
     *  coupled motion and decouples the crank without moving levers; a lever up PARKS its motion (it keeps its
     *  speed, ignores trim and crank) or switches it off at rest. Off: the focus model (drive / hold per lever). */
    public boolean coupling = true;
    /** wells: the third crank model. No friction; the brake (progressive) is the only way down; released inside the
     *  acceptance window of an integer, the crystal eases the crank onto it (the well) — released outside, it sits detuned.
     *  The technique is spin past, brake, let go as the figure's roll stops. The window is constant across ratios.
     *  Classic wins if both are set. */
    public boolean wells = true, brake; public double brakeHeld;   // brakeHeld: seconds the brake has been on, for the progressive bite
    public final java.util.Random notchRng = new java.util.Random(11);
    /** free: the machine as the game has it, with no pinned recipe. What the levers allow is the machine's own
     *  (freeArms × freeMotions per arm: its upgrade path), never a recipe's tier, so a recipe is out of reach when the
     *  machine cannot make its motions. score and fit follow the best-scoring recipe (best); lock / unlock report any
     *  recipe held exactly (matched), and voice() writes that one. target is left alone and decides nothing (the lab
     *  still draws its blueprint). Off (the default), everything is the pinned machine's, unchanged. */
    public boolean free;
    public int freeArms = ARMS, freeMotions = AXES;
    /** Free machine, as of the last tick: the recipe the score follows (null while nothing scores) and the recipe held
     *  exactly while powered (null if none). lastVoiced: the recipe the last voice() wrote, in either mode. */
    public Recipe best, matched, lastVoiced;
    private boolean freeLocked;
    public double snapTol = 0.1;          // the acceptance window at ×1 (the difficulty scaler); narrower for higher ratios (constant under wells)
    public final java.util.Map<String, Eval> eval = new java.util.LinkedHashMap<>();
    public Eval targetEval = new Eval();
    public final double[] signals = new double[SIGNALS.length];
    public final java.util.Set<String> discovered = new java.util.HashSet<>();
    private final java.util.Map<String, Boolean> prevExact = new java.util.HashMap<>();
    private final java.util.List<String> events = new java.util.ArrayList<>();
    private final java.util.List<java.util.List<Snap>> voiced = new java.util.ArrayList<>();   // one snapshot per voiced crystal

    public RegulatorCore() { this(DEFAULT_RECIPES); }
    public RegulatorCore(Recipe[] recipes) {
        this.recipes = recipes;
        for (Motion[] a : comps) for (int i = 0; i < AXES; i++) a[i] = new Motion();
        for (Recipe r : recipes) eval.put(r.id, new Eval());
        if (recipes.length > 0) target = recipes[0];
    }
    public Recipe recipe(String id) { for (Recipe r : recipes) if (r.id.equals(id)) return r; return null; }
    /** The free machine (the game's): this many arms, each holding this many motions; the arms are cleared. */
    public void setFree(int arms, int motionsPerArm) {
        free = true;
        freeArms = Math.max(1, Math.min(ARMS, arms)); freeMotions = Math.max(1, Math.min(AXES, motionsPerArm));
        resetComps(); arm = 0;
    }
    /** How many arms the levers reach, and how many motions each may hold: the machine's own when free, else the pinned recipe's tier. */
    public int arms() { return free ? freeArms : target != null ? target.arms() : 0; }
    public int motionsPerArm() { return free ? freeMotions : target != null ? target.motionsPerArm() : 0; }
    /** The recipe these motions trace exactly, or null: what a host checks before it trusts a claimed match. */
    public static Recipe exactMatch(Recipe[] recipes, java.util.List<Eng> eng) { for (Recipe r : recipes) if (evaluate(r, eng).exact) return r; return null; }
    public void resetComps() { for (Motion[] a : comps) for (int i = 0; i < AXES; i++) a[i] = new Motion(); java.util.Arrays.fill(focus, -1); }
    /** Events since the last drain: lock, unlock (the target), discover:<id>, wrong:<id> (another blueprint's
     *  sigil matched), voice, stopped:<arm>:<axis>. */
    public java.util.List<String> events() { java.util.List<String> out = new java.util.ArrayList<>(events); events.clear(); return out; }
    public java.util.List<java.util.List<Snap>> voiced() { return voiced; }

    // ---- crank
    public double crankRatio() { return 2 * Math.abs(vel); }
    public static double catchWidth(int n) { return CATCH_W / n; }
    /** A scroll notch / button press: dir ±1, step in vel units (NUDGE_*). Sets the slip timer. */
    /** Coupled-lever model: touching the crank couples it to every active motion, at the slowest one already turning. */
    public void couple() {
        if (!coupling) return;
        double slowest = Double.MAX_VALUE; boolean any = false;
        for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.act) { any = true; if (c.r >= REST_R) slowest = Math.min(slowest, c.r); }
        if (!any) return;
        if (slowest < Double.MAX_VALUE) { double sg = Math.signum(vel); if (sg == 0) sg = 1; vel = sg * slowest / 2; }
        double cr = 2 * Math.abs(vel);
        for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.act) { c.drv = true; c.r = cr; }
        caught = -1;
    }
    public void nudge(int dir, double step) {
        if (wells && !classic) {
            if (dir < 0) return;   // the wheel only goes up: the brake is the way down
            step *= 1 + (notchRng.nextDouble() * 2 - 1) * NOTCH_JITTER;   // an imprecise notch: look and listen, don't count
        }
        couple();
        double s = Math.signum(vel); if (s == 0) s = 1;
        vel = Math.max(-MAX_VEL, Math.min(MAX_VEL, vel + s * dir * step));
        if (Math.abs(vel) < 0.001) vel = 0;
        slip = SLIP_NUDGE;
        double cr = crankRatio();   // the driven motions follow at once, so a latch in the same frame sees the notch (tick repeats this)
        for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.drv) c.r = cr;
    }
    public void dragStart() { couple(); drag = true; }
    /** The brake (wells model): taking hold of it couples the active motions, as any touch on the crank does. */
    public void setBrake(boolean on) { if (on && !brake) { couple(); brakeHeld = 0; } brake = on; }
    /** While dragging: the measured crank speed in rev/s (the pointer's angular velocity), smoothed in. */
    public void dragVelocity(double revPerSec) { if (!drag) return; double v = Math.max(-MAX_VEL, Math.min(MAX_VEL, revPerSec)); vel += (v - vel) * DRAG_SMOOTH; }
    public void dragEnd() { drag = false; slip = SLIP_DRAG; }
    /** Loads a held motion's ratio into the crank (held → driven with no others driven). */
    public void loadCrank(double r) { vel = r / 2; slip = 0; int n = (int) Math.round(r); caught = classic && Math.abs(r - n) < 1e-6 ? n : -1; }
    /** Free crank: how close to integer n a latched ratio must be for the crystal to take it. */
    public double acceptWindow(int n) { return wells && !classic ? snapTol : snapTol / Math.sqrt(n); }
    /** Wells model: 1 once the crystal has taken the crank onto an integer (released, settled), else 0 — a confirmation, not a guide. */
    public double wellDepth() {
        if (!wells || classic || brake || slip > 0) return 0;
        double r = crankRatio(); int n = (int) Math.round(r);
        return n >= 1 && n <= MAX_N && Math.abs(r - n) < 1e-3 ? 1 : 0;
    }
    /** Free crank: the integer this ratio would be accepted as on latch, or -1. */
    public int acceptable(double r) { int n = (int) Math.round(r); return n >= 1 && n <= MAX_N && Math.abs(r - n) <= acceptWindow(n) ? n : -1; }
    public void updateCrank(double dt) {
        slip = Math.max(0, slip - dt);
        if (!drag) {
            double r = 2 * Math.abs(vel); int n = (int) Math.round(r);
            double sg = Math.signum(vel); if (sg == 0) sg = 1;
            if (slip <= 0 && n == 0 && r < REST_R) {
                vel *= Math.exp(-dt * REST_RATE);
                if (Math.abs(vel) < 5e-4) vel = 0;
                caught = 0;
            } else if (classic && slip <= 0 && n >= 1 && n <= MAX_N && Math.abs(r - n) < catchWidth(n)) {
                double tv = sg * n / 2;
                vel += (tv - vel) * Math.min(1, dt * CATCH_RATE);
                if (Math.abs(vel - tv) < 2e-4) vel = tv;
                caught = n;
            } else if (wells && !classic) {
                caught = -1;
                double dr = 0, d = r - n, w = n >= 1 && n <= MAX_N ? acceptWindow(n) : 0;   // change in ratio this tick; the window around the nearest integer
                boolean inWin = w > 0 && Math.abs(d) <= w;
                if (brake) { brakeHeld += dt; dr -= BRAKE_RATE * (1 + (BRAKE_BITE - 1) * Math.min(1, brakeHeld / BRAKE_RAMP)) * dt; }
                else {
                    brakeHeld = 0;
                    if (slip <= 0 && inWin) dr -= d * Math.min(1, dt / WELL_TAU);   // let go inside the window: the crystal eases it onto the integer
                    if (r < 1 - w && slip <= 0) dr -= 2 * BRAKE * dt;               // the dead zone under the first resonance: rest is still a catch point
                }
                double nr = Math.max(0, r + dr);
                if (!brake && slip <= 0 && inWin && Math.abs(nr - n) < 5e-4) nr = n;   // settled: exactly on it
                vel = sg * nr / 2;
            } else {
                caught = -1;
                vel *= Math.exp(-FRICTION * dt);
                if (r < 1 && slip <= 0) vel = sg * Math.max(0, Math.abs(vel) - BRAKE * dt);   // the dead zone under the first resonance
            }
        } else caught = -1;
        ang += vel * dt * 360;
        double cr = 2 * Math.abs(vel);
        for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.drv) c.r = cr;
    }

    // ---- levers
    public int drivenCount() { int n = 0; for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.drv) n++; return n; }
    public int engagedCount(int arm) { int n = 0; for (Motion c : comps[arm]) if (c.eng) n++; return n; }
    public boolean selectArm(int i) { if (i < 0 || i >= arms()) return false; arm = i; return true; }
    public void setTarget(Recipe r) { target = r; resetComps(); arm = 0; }
    public void power(boolean on) { powered = on; }
    /** driven → held; a motion held at rest is switched off (that is how motions are released). Classic crank: a
     *  caught ratio is written exactly. Free crank: a ratio within the acceptance window snaps to the integer (the
     *  crystal answers: event accept:<arm>:<axis>:<n>), anything else is held detuned (event hold:<arm>:<axis>). */
    private boolean holdOrStop(Motion c, int arm, int ax) {
        if (classic) { if (caught >= 0) c.r = caught; }
        else if (c.r >= REST_R) {
            int n = acceptable(c.r);
            if (n > 0) { c.r = n; events.add("accept:" + arm + ":" + ax + ":" + n); }
            else events.add("hold:" + arm + ":" + ax);
        } else c.r = 0;
        c.drv = false;
        if (c.r < 1e-6) { c.eng = false; c.r = 0; return true; }
        return false;
    }
    /** The axis lever of the selected arm: off → driven (from rest, or joining the others at the crank's
     *  ratio), driven → held (or off at rest), held → driven (loading the crank, or ganging). Returns false
     *  when the tier allows no more motions on this arm. */
    public boolean axisLever(int ax) {
        if (!free && target == null) return false;
        Motion c = comps[arm][ax];
        if (coupling) {
            if (!c.eng) {   // off → active, at rest; it joins the crank on the next scroll
                if (engagedCount(arm) >= motionsPerArm()) return false;
                c.eng = true; c.act = true; c.drv = false; c.r = 0; c.ph = 0; c.amp = DEFAULT_REACH; c.osc = 0;
            } else if (c.act) {   // active → parked (keeps its speed), or off at rest
                if (c.drv) holdOrStop(c, arm, ax);   // a coupled motion is latched as it goes up
                c.act = false; c.drv = false;
                if (c.r < REST_R) { c.eng = false; c.r = 0; events.add("stopped:" + arm + ":" + ax); }
            } else c.act = true;   // parked → active again
            return true;
        }
        int others = drivenCount();
        if (!c.eng) {
            if (engagedCount(arm) >= motionsPerArm()) return false;   // refused: the trim focus stays where it was
            focus[arm] = ax;
            c.eng = true; c.drv = true; c.ph = 0; c.amp = DEFAULT_REACH; c.osc = 0;
            if (others > 0) c.r = crankRatio(); else { c.r = 0; loadCrank(0); }
        } else if (c.drv) {
            focus[arm] = ax;
            if (holdOrStop(c, arm, ax)) { events.add("stopped:" + arm + ":" + ax); focus[arm] = -1; }
        } else {
            focus[arm] = ax;
            c.drv = true;
            if (others > 0) c.r = crankRatio(); else loadCrank(c.r);
        }
        return true;
    }
    /** Latch: every driven motion is held (snapped to the caught integer), or switched off at rest. Returns how many stopped. */
    public int latch() {
        int stopped = 0;
        for (int a = 0; a < ARMS; a++) for (int x = 0; x < AXES; x++) {
            Motion c = comps[a][x];
            if (c.eng && c.drv && holdOrStop(c, a, x)) { stopped++; events.add("stopped:" + a + ":" + x); }
        }
        return stopped;
    }
    /** Focus a motion of the selected arm for the trim controls without changing its state. */
    public boolean focusAxis(int ax) { if (ax < 0 || ax >= AXES || !comps[arm][ax].eng) return false; focus[arm] = ax; return true; }
    /** The motion the trim controls act on: the focused axis of the selected arm (engaged), else null. */
    public Motion focused() { int ax = focus[arm]; return ax >= 0 && comps[arm][ax].eng ? comps[arm][ax] : null; }
    /** The motions the trim controls act on: every active one (coupled-lever model), else the focused one. */
    public java.util.List<Motion> trimmed() {
        java.util.List<Motion> out = new java.util.ArrayList<>();
        if (coupling) { for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.act) out.add(c); }
        else { Motion c = focused(); if (c != null) out.add(c); }
        return out;
    }
    public int activeCount() { int n = 0; for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.act) n++; return n; }
    /** The phase dial: the trimmed motions turn a quarter cycle. */
    public void phaseStep() { for (Motion c : trimmed()) c.ph = (c.ph + 1) % 4; }
    /** The reach control: the trimmed motions take this amplitude. */
    public void setReach(double amp) { for (Motion c : trimmed()) c.amp = amp; }
    /** The first driven motion (what the trim controls show), or null. */
    public Motion firstDriven() { for (Motion[] a : comps) for (Motion c : a) if (c.eng && c.drv) return c; return null; }

    // ---- matching
    public record Eng(int arm, int axis, double r, int ph, double amp) {}
    public java.util.List<Eng> engaged() {
        java.util.List<Eng> out = new java.util.ArrayList<>();
        for (int a = 0; a < ARMS; a++) for (int x = 0; x < AXES; x++) {
            Motion c = comps[a][x];
            if (c.eng && c.amp > ENGAGE_AMP && c.r > ENGAGE_R) out.add(new Eng(a, x, c.r, c.ph, c.amp));
        }
        return out;
    }
    public static Eval evalOnce(Comp[] comps, java.util.List<Eng> eng) { return evalOnce(comps, eng, DEFAULT_RTOL); }
    public static Eval evalOnce(Comp[] comps, java.util.List<Eng> eng, double rtol) {
        boolean[] used = new boolean[eng.size()];
        double sum = 0, fitSum = 0; boolean exact = true; int nUsed = 0;
        for (Comp t : comps) {
            int best = -1; double bs = 0; boolean bx = false;
            for (int i = 0; i < eng.size(); i++) {
                Eng e = eng.get(i);
                if (used[i] || e.axis != t.axis) continue;
                double d = Math.abs(e.r - t.n);
                boolean reachOk = Math.abs(e.amp - t.amp) <= rtol + 1e-9;
                double s = Math.exp(-d * 5) * (e.ph == t.phase ? 1 : 0.5) * (reachOk ? 1 : 0.7);
                if (s > bs) { bs = s; best = i; bx = d < 1e-6 && e.ph == t.phase && reachOk; }
            }
            if (best >= 0) { used[best] = true; nUsed++; sum += bs; if (!bx) exact = false; fitSum += Math.exp(-6 * Math.abs(eng.get(best).amp - t.amp)); }
            else exact = false;
        }
        int extra = eng.size() - nUsed;
        if (extra > 0) exact = false;
        Eval ev = new Eval();
        ev.score = sum / comps.length * Math.pow(0.6, extra);
        ev.exact = exact;
        ev.fit = nUsed > 0 ? fitSum / nUsed : 0;   // over the matched components only: reach quality, not recipe progress
        return ev;
    }
    /** The best score over every phase set that traces the recipe's figure (§3.4). */
    public static Eval evaluate(Recipe rec, java.util.List<Eng> eng) {
        Eval best = new Eval();
        for (Comp[] cs : rec.variants()) {
            Eval e = evalOnce(cs, eng, rec.rtol);
            if (e.exact) return e;
            if (e.score > best.score) best = e;
        }
        return best;
    }

    // ---- the frame: physics, evaluation, events, signals
    /** Coupled-lever model: the crank is coupled to at least one active motion. */
    public boolean coupled() { return drivenCount() > 0; }
    public void tick(double dt) {
        tau += dt;
        updateCrank(dt);
        // every engaged motion runs its own oscillator; one held exactly on an integer eases into alignment with
        // the receiver (its angle → ratio × receiver angle), which is what makes the dial's quarters meaningful
        for (Motion[] a : comps) for (Motion c : a) {
            if (!c.eng) continue;
            c.osc += c.r * DRAW_RATE * dt;
            if (!c.drv && c.r == Math.rint(c.r)) {
                double want = c.r * DRAW_RATE * tau, d = want - c.osc;
                d -= 2 * Math.PI * Math.rint(d / (2 * Math.PI));
                c.osc += d * Math.min(1, dt * 4);
            }
            if (c.osc > 1e6) c.osc -= 2 * Math.PI * Math.floor(c.osc / (2 * Math.PI));
        }
        java.util.List<Eng> eng = engaged();
        Recipe bestNow = null, exactNow = null; double bestScore = 0;
        for (Recipe r : recipes) {
            Eval e = evaluate(r, eng);
            eval.put(r.id, e);
            if (e.exact && exactNow == null) exactNow = r;
            if (e.score > bestScore) { bestScore = e.score; bestNow = r; }
            boolean was = prevExact.getOrDefault(r.id, false);
            if (e.exact && !was && powered) {
                events.add("match:" + r.id);   // every recipe reports; lock / unlock are the pinned target's (free: any recipe's, below)
                if (free) { if (r.secret && discovered.add(r.id)) events.add("discover:" + r.id); }
                else if (r == target) events.add("lock");
                else if (r.secret) { if (discovered.add(r.id)) events.add("discover:" + r.id); }
                else events.add("wrong:" + r.id);
            } else if (!e.exact && was) { events.add("unmatch:" + r.id); if (!free && r == target) events.add("unlock"); }
            prevExact.put(r.id, e.exact && powered);
        }
        if (free) {   // no pin: the score follows the best recipe, and lock is the machine holding any recipe exactly
            best = exactNow != null ? exactNow : bestNow;
            matched = powered ? exactNow : null;
            if (matched != null && !freeLocked) events.add("lock"); else if (matched == null && freeLocked) events.add("unlock");
            freeLocked = matched != null;
            targetEval = best != null ? eval.get(best.id) : new Eval();
        } else targetEval = target != null ? eval.get(target.id) : new Eval();
        computeSignals(eng);
    }
    public void computeSignals(java.util.List<Eng> eng) {
        java.util.Arrays.fill(signals, 0, S_ORB_SPEED, 0);   // the orb envelopes persist (smoothed across ticks)
        for (int a = 0; a < ARMS; a++) {
            double best = -1, reach = 0;
            for (Eng e : eng) if (e.arm == a) { reach += e.amp; if (e.amp > best) { best = e.amp; signals[S_RATIO + a] = e.r; } }
            signals[S_REACH + a] = Math.min(1, reach);
        }
        double z = 0, coh = 0, cons = 0, tension = 0; int pairs = 0;
        for (int i = 0; i < eng.size(); i++) {
            Eng e = eng.get(i);
            if (e.axis == 2) z += e.amp;
            coh += Math.exp(-8 * Math.abs(e.r - Math.round(e.r)));
            tension = Math.max(tension, e.r);
            for (int j = i + 1; j < eng.size(); j++) {
                int ni = Math.max(1, (int) Math.round(e.r)), nj = Math.max(1, (int) Math.round(eng.get(j).r)), g = gcd(ni, nj);
                cons += 2.0 / (ni / g + nj / g);
                pairs++;
            }
        }
        signals[S_RADIANCE] = Math.min(1, z);
        signals[S_CONSONANCE] = pairs > 0 ? cons / pairs : eng.size() == 1 ? 1 : 0;
        signals[S_TENSION] = Math.min(1, tension / MAX_N);
        signals[S_DRIVE] = Math.min(1, crankRatio() / MAX_N);
        signals[S_COHERENCE] = eng.isEmpty() ? 0 : coh / eng.size();
        signals[S_SCORE] = targetEval.score;
        for (int t = 0; t < TONE_ST.length; t++) {
            double sum = 0;
            for (Eng e : eng) {
                double d = Math.abs(pitchOf(e.r) - TONE_ST[t]); d = Math.min(d, 12 - d);   // circular semitone distance
                sum += Math.sqrt(e.amp) * Math.exp(-TONE_K * d);   // √reach: a quarter-reach chord tone still sings at half
            }
            signals[S_TONE + t] = Math.min(1, sum);
        }
        signals[S_STACK] = Math.min(1, eng.size() / 6.0);   // how full the machine is: a tier-III recipe's six motions = 1
        signals[S_FIT] = targetEval.fit;                     // the reach hint: 1 when every matched motion's reach is on the pinned recipe's target
        orbSignals();
    }
    // ---- the orb's kinematics, from the oscillators' derivatives (exact, whatever the frame rate), each an
    // envelope smoothed over ORB_SMOOTH so binds get a contour rather than the pen's every wobble
    private final double[] orbP = new double[3], orbV = new double[3], orbA = new double[3];
    private double lastOrbTau = -1;
    public void orbSignals() {
        double vmax = 0, amax = 0, ext = extent();
        java.util.Arrays.fill(orbP, 0); java.util.Arrays.fill(orbV, 0); java.util.Arrays.fill(orbA, 0);
        for (Motion[] a : comps) for (int ax = 0; ax < AXES; ax++) {
            Motion c = a[ax];
            if (!c.eng) continue;
            double amp = c.amp * Math.min(1, c.r / 0.6), w = c.r * DRAW_RATE, th = c.osc + c.ph * Math.PI / 2;
            orbP[ax] += amp * Math.sin(th); orbV[ax] += amp * w * Math.cos(th); orbA[ax] -= amp * w * w * Math.sin(th);
            vmax += amp * w; amax += amp * w * w;
        }
        double v = Math.sqrt(orbV[0] * orbV[0] + orbV[1] * orbV[1] + orbV[2] * orbV[2]);
        double acc = Math.sqrt(orbA[0] * orbA[0] + orbA[1] * orbA[1] + orbA[2] * orbA[2]);
        double cx = orbV[1] * orbA[2] - orbV[2] * orbA[1], cy = orbV[2] * orbA[0] - orbV[0] * orbA[2], cz = orbV[0] * orbA[1] - orbV[1] * orbA[0];
        double kappa = v > 1e-6 ? Math.sqrt(cx * cx + cy * cy + cz * cz) / (v * v * v) : 0;   // curvature: 1/radius of the loop being drawn
        double speed = vmax > 0 ? v / vmax : 0;                       // 1 = every motion pulling the same way at once
        double accel = amax > 0 ? acc / amax : 0;
        double curl = Math.min(1, kappa * ext / 4);                    // a circle at full extent = 0.25, a loop a quarter that size = 1
        double radius = Math.min(1, Math.sqrt(orbP[0] * orbP[0] + orbP[1] * orbP[1] + orbP[2] * orbP[2]) / ext);
        double k = lastOrbTau < 0 ? 1 : 1 - Math.exp(-(tau - lastOrbTau) / ORB_SMOOTH), ks = lastOrbTau < 0 ? 1 : 1 - Math.exp(-(tau - lastOrbTau) / STIR_SMOOTH);
        lastOrbTau = tau;
        // stir: 0 with every arm at rest, 1 once anything turns at ×0.5 or faster (whatever the crank is doing)
        double fastest = 0;
        for (Eng e : engaged()) fastest = Math.max(fastest, e.r);
        signals[S_STIR] += (Math.min(1, fastest / 0.5) - signals[S_STIR]) * ks;
        signals[S_ORB_SPEED] += (speed - signals[S_ORB_SPEED]) * k;
        signals[S_ORB_ACCEL] += (accel - signals[S_ORB_ACCEL]) * k;
        signals[S_ORB_CURL] += (curl - signals[S_ORB_CURL]) * k;
        signals[S_ORB_RADIUS] += (radius - signals[S_ORB_RADIUS]) * k;
    }
    public static int gcd(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t; } return a; }
    /** arm{n}.pitch: the arm's ratio as semitones folded into one octave (0 when the arm is silent). */
    public double pitch(int arm) { return pitchOf(signals[S_RATIO + arm]); }
    public static double pitchOf(double ratio) { if (ratio <= 0.05) return 0; double st = 12 * Math.log(ratio) / Math.log(2); return ((st % 12) + 12) % 12; }

    // ---- the figure (§3.3, revised): a pen. The receiver's own cycle takes DRAW_PERIOD seconds of machine time;
    // every motion oscillates at its ratio times that, so integer ratios retrace one closed figure and a detuned
    // motion makes the trace precess at a rate proportional to the detune, slowing to a stop as it is tuned in.
    public static final double DRAW_PERIOD = 2.5, DRAW_RATE = 2 * Math.PI / DRAW_PERIOD;
    /** The pen's position at machine time tauAt (between the last tick and the next, extrapolated at each motion's rate). */
    public void pen(double tauAt, double[] out) {
        out[0] = out[1] = out[2] = 0;
        for (Motion[] a : comps) for (int ax = 0; ax < AXES; ax++) {
            Motion c = a[ax];
            if (!c.eng) continue;
            out[ax] += c.amp * Math.min(1, c.r / 0.6) * Math.sin(c.osc + c.r * DRAW_RATE * (tauAt - tau) + c.ph * Math.PI / 2);
        }
    }
    /** One arm's own contribution to the pen (for drawing the arm heads). */
    public void armPen(int arm, double tauAt, double[] out) {
        out[0] = out[1] = out[2] = 0;
        for (int ax = 0; ax < AXES; ax++) {
            Motion c = comps[arm][ax];
            if (!c.eng) continue;
            out[ax] += c.amp * Math.min(1, c.r / 0.6) * Math.sin(c.osc + c.r * DRAW_RATE * (tauAt - tau) + c.ph * Math.PI / 2);
        }
    }
    /** The figure's shape as the recipe would draw it: every motion phase-locked to the receiver, over one receiver
     *  cycle t ∈ [0, 2π). Closed only for integer ratios. */
    public void figurePoint(double t, double[] out) {
        out[0] = out[1] = out[2] = 0;
        for (Motion[] a : comps) for (int ax = 0; ax < AXES; ax++) {
            Motion c = a[ax];
            if (!c.eng) continue;
            out[ax] += c.amp * Math.min(1, c.r / 0.6) * Math.sin(c.r * t + c.ph * Math.PI / 2);
        }
    }
    /** Per-axis extent of the engaged motions (summed reach), floored at 0.7 like the prototype's stage. */
    public double extent() { double m = 0.7; for (int ax = 0; ax < AXES; ax++) { double s = 0; for (Motion[] a : comps) if (a[ax].eng) s += a[ax].amp; m = Math.max(m, s); } return m; }

    // ---- the blueprint (§3.6): a damped harmonograph trace of the recipe
    public static final int BP_FRONT = 0, BP_TOP = 1, BP_POINTS = 2401;
    /** Points {h, v} of the recipe's trace in one view, in figure units (divide by extent(rec) to fit).
     *  Front: h = X, v = Y up. Top: h = X, v = −Z, so +Z draws toward the bottom, matching the machine's top camera. */
    public static double[][] blueprint(Recipe rec, int view) {
        double[][] out = new double[BP_POINTS][2];
        int ha = 0, va = view == BP_TOP ? 2 : 1;
        double T = Math.PI * 2 * 4;
        double[] p = new double[3];
        for (int i = 0; i < BP_POINTS; i++) {
            double t = i / (double) (BP_POINTS - 1) * T, damp = Math.exp(-0.022 * t);
            p[0] = p[1] = p[2] = 0;
            for (int j = 0; j < rec.comps.length; j++) {
                Comp c = rec.comps[j];
                double drift = 0.0025 * (j % 2 == 1 ? 1 : -1) * (1 + j * 0.3);
                p[c.axis] += c.amp * Math.sin(c.n * t + c.phase * Math.PI / 2 + drift * t);
            }
            out[i][0] = p[ha] * damp;
            out[i][1] = (view == BP_TOP ? -1 : 1) * p[va] * damp;
        }
        return out;
    }
    public static double extent(Recipe rec) { double m = 0.7; for (int ax = 0; ax < AXES; ax++) { double s = 0; for (Comp c : rec.comps) if (c.axis == ax) s += c.amp; m = Math.max(m, s); } return m; }
    /** Authoring check: true when the recipe's curve retraces itself into an open line (some c has
     *  p(c − t) = p(t) for all t, e.g. sin 3t against cos 2t). Valid but reads poorly as a sigil. */
    public static boolean degenerate(Recipe rec) {
        int N = 240;
        double[][] pts = new double[N][3];
        for (int i = 0; i < N; i++) {
            double t = 2 * Math.PI * i / N;
            for (Comp c : rec.comps) pts[i][c.axis] += c.amp * Math.sin(c.n * t + c.phase * Math.PI / 2);
        }
        for (int k = 0; k < N; k++) {   // candidate c = 2π k / N: compare p(t) with p(c − t)
            double worst = 0;
            for (int i = 0; i < N && worst < 1e-6; i++) {
                int j = ((k - i) % N + N) % N;
                for (int ax = 0; ax < 3; ax++) worst = Math.max(worst, Math.abs(pts[i][ax] - pts[j][ax]));
            }
            if (worst < 1e-6) return true;
        }
        return false;
    }

    // ---- research setpoint, copy socket, voicing
    /** The recipe laid onto arms the way the station would: greedily, one motion per arm-axis within the tier. */
    public static java.util.List<Snap> recipeSnapshot(Recipe rec) {
        int arms = rec.arms(), per = rec.motionsPerArm();
        int[] load = new int[ARMS]; boolean[] used = new boolean[ARMS * AXES];
        java.util.List<Snap> snap = new java.util.ArrayList<>();
        for (Comp cp : rec.comps)
            for (int a = 0; a < arms; a++)
                if (load[a] < per && !used[a * 3 + cp.axis]) { used[a * 3 + cp.axis] = true; load[a]++; snap.add(new Snap(a, cp.axis, cp.n, cp.phase, cp.amp)); break; }
        return snap;
    }
    /** Loads motions with ratios jittered by ±jit·(0.5..1) (never below 0.5), every motion held; with phaseErr one
     *  random phase is a quarter off. Setpoint: recipeSnapshot(target), SETPOINT_JITTER, true. Copy socket: a
     *  voiced crystal's snapshot, SOCKET_JITTER, false. */
    public void applySnapshot(java.util.List<Snap> snap, double jit, boolean phaseErr, java.util.Random rng) {
        resetComps();
        int wrong = phaseErr && !snap.isEmpty() ? rng.nextInt(snap.size()) : -1;
        for (int i = 0; i < snap.size(); i++) {
            Snap s = snap.get(i);
            Motion c = comps[s.arm][s.axis];
            c.eng = true; c.drv = false; c.act = false; c.amp = s.amp;
            double d = (rng.nextDouble() < 0.5 ? -1 : 1) * (jit * (0.5 + rng.nextDouble() * 0.5));
            c.r = Math.max(0.5, s.r + d);
            c.ph = i == wrong ? (s.phase + 1) % 4 : s.phase;
            c.osc = c.r * DRAW_RATE * tau;
            if (focus[s.arm] < 0) focus[s.arm] = s.axis;
        }
        arm = 0;
    }
    public void loadSetpoint(java.util.Random rng) { if (target != null) applySnapshot(recipeSnapshot(target), SETPOINT_JITTER, true, rng); }
    /** Every engaged motion as it stands. */
    public java.util.List<Snap> snapshot() {
        java.util.List<Snap> out = new java.util.ArrayList<>();
        for (int a = 0; a < ARMS; a++) for (int x = 0; x < AXES; x++) { Motion c = comps[a][x]; if (c.eng) out.add(new Snap(a, x, c.r, c.ph, c.amp)); }
        return out;
    }
    /** The voice lever: only at an exact match. Writes the sigil (returned) and clears the machine for a fresh crystal.
     *  Pinned, the match is the target's; free, any recipe's, judged on the motions as they stand (not the last tick's
     *  view of them). lastVoiced names the recipe written. */
    public java.util.List<Snap> voice() {
        Recipe r = free ? (powered ? exactMatch(recipes, engaged()) : null) : target != null && targetEval.exact ? target : null;
        if (r == null) return null;
        lastVoiced = r;
        java.util.List<Snap> snap = snapshot();
        voiced.add(snap);
        resetComps();
        events.add("voice");
        return snap;
    }
}
