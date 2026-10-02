package sfxlab.runtime;

/**
 * The Harmonic Regulator as it is conducted (the mod's docs/resonance_chamber.md;
 * here, docs/HARMONIC-REGULATOR.md §8): the core, worked one <b>component</b> at
 * a time with no crank, no levers and no latch. This is the machine as the game
 * plays it, and the lab's machine window is the same class. A component is a motion: an axis and a slot on it
 * (up to three an axis). One is <b>selected</b> at a time (the aiming arm's);
 * its ratio is the array's aim, wound and caught in a station's well; its
 * reach and phase are turned; planting it is leaving it where it stands and
 * selecting another. The aim is shared, so a component started after
 * another begins at the ratio just left.
 *
 * <b>The aim is the machine's own.</b> It goes with a component while that
 * component is wound or drawn in by a well, and turns to a planted
 * component taken back in hand. Nothing else moves it: not a component
 * shrunk to nothing, not planting, not the next arm.
 *
 * Built on the core with nothing in it changed: one motion
 * in hand is the core's focus model (one driven motion on the crank, the
 * rest held), a push writes the crank's speed, and the core's own well
 * draws a motion let go inside a resonance's window onto the integer.
 * Settled there, the crystal takes it (the core's latch, called for the
 * player). Let go anywhere else it hangs where it is, under the first
 * resonance as above it: the machine switches nothing off. A component
 * with reach at the zero position is a component, sitting still; it is
 * gone only when its reach is taken to nothing.
 *
 * Pure: no game in it, so the tests work it as a hand would.
 */
public final class ConductedMachine {

    /** As far as the aim winds: past the last station far enough to overshoot it and tumble, short of ×8, which
     *  the core would accept and no recipe uses. */
    public static final double AIM_MAX = 7.5;

    /** While a motion is pushed the well must not pull: the core's slip timer, kept topped up. Seconds. */
    private static final double HELD_SLIP = 0.12;

    /** A verb's rate at a moment: ratio a second, after {@code held} seconds of the channel. */
    public record Curve(double rate, double quickenSeconds, double rateMax) {

        /** Steady from the first moment: crescendo. */
        public static Curve linear(double rate) {
            return new Curve(rate, 0, rate);
        }

        /** Slow at first, quickening the longer it is held, to a ceiling: diminuendo. */
        public static Curve quickening(double rate, double quickenSeconds, double rateMax) {
            return new Curve(rate, quickenSeconds, rateMax);
        }

        public double at(double held) {
            if (quickenSeconds <= 0) return rate;
            return Math.min(rateMax, rate * Math.exp(Math.max(0, held) / quickenSeconds));
        }

        /** How far the ratio moves over a tick that ends {@code held} seconds into the channel. */
        public double over(double held, double dt) {
            return at(held) * dt;
        }
    }

    /**
     * A verb of pure anima as the game ships it (the mod's abilities/elements/pure_anima/): how fast it moves the
     * aim, and at what scale the same curve moves a reach or a phase.
     */
    public record Verb(Curve curve, double reachRate, double phaseRate, int sign) {
        /** The aim's change over a tick ending {@code held} seconds into the channel, in ratio. */
        public double aim(double held, double dt) { return sign * curve.over(held, dt); }
        public double reach(double held, double dt) { return aim(held, dt) * reachRate / curve.rate(); }
        /** In quarters. */
        public double phase(double held, double dt) { return aim(held, dt) * phaseRate / curve.rate(); }
    }

    /** Winds up, steadily and fast, so it tends to carry past. */
    public static final Verb CRESCENDO = new Verb(Curve.linear(1.2), 0.4, 1.0, +1);
    /** Damps, gently at first and harder the longer it is held: a pulse feathers, a hold clears. */
    public static final Verb DIMINUENDO = new Verb(Curve.quickening(0.06, 0.9, 4.0), 0.4, 1.0, -1);
    /** Crescendo held this long on the seated crystal while a sigil holds voices it, seconds. */
    public static final double VOICE_SECONDS = 0.4;

    /** The phase well: a tilt let go is drawn onto the nearest quarter with this time constant, seconds. The core
     *  stores quarters, so a tilt never hangs between them the way a ratio hangs between stations. */
    public static final double PHASE_WELL_TAU = 0.3;

    public final RegulatorCore core;
    private final int[][] planPhase = new int[RegulatorCore.AXES][RegulatorCore.ARMS];
    private final double[][] planReach = new double[RegulatorCore.AXES][RegulatorCore.ARMS];
    /** Each component's phase as a continuous tilt, quarters (0..4): what is drawn and turned; the core sees the nearest quarter. */
    private final double[][] phaseAngle = new double[RegulatorCore.AXES][RegulatorCore.ARMS];
    private final boolean[][] phaseInHand = new boolean[RegulatorCore.AXES][RegulatorCore.ARMS];
    private int axis = 0, slot = 0;
    private boolean inHand;
    /** The array's aim, as a ratio. The machine's own: see the head of the class for what moves it. */
    private double aim;

    public ConductedMachine(RegulatorCore.Recipe[] recipes, int slots, int axes) {
        core = new RegulatorCore(recipes);
        core.setFree(slots, axes);
        core.coupling = false;
        for (double[] a : planReach) java.util.Arrays.fill(a, RegulatorCore.DEFAULT_REACH);
    }

    /** The core keeps a motion by arm and axis; conducted, the core's arm is the motion's slot on its axis. */
    public RegulatorCore.Motion motion(int axis, int slot) {
        return core.comps[slot][axis];
    }

    public int slots() {
        return core.freeArms;
    }

    public boolean has(int axis, int slot) {
        return axis >= 0 && axis < RegulatorCore.AXES && slot >= 0 && slot < core.freeArms;
    }

    // ---- the plan ----

    public int phase(int axis, int slot) {
        return planPhase[axis][slot];
    }

    public double reach(int axis, int slot) {
        return planReach[axis][slot];
    }

    public void plan(int axis, int slot, int phase, double reach) {
        if (!has(axis, slot)) return;
        planPhase[axis][slot] = ((phase % 4) + 4) % 4;
        planReach[axis][slot] = Math.max(0, Math.min(1, reach));
        RegulatorCore.Motion m = motion(axis, slot);
        m.ph = planPhase[axis][slot];
        m.amp = planReach[axis][slot];
    }

    /** After the arms are cleared (a voicing, the crystal taken) the plan stands: it is the room's, not the crystal's. */
    public void clear() {
        core.resetComps();
        core.vel = 0;
        core.slip = 0;
        axis = 0;
        slot = 0;
        inHand = false;
        aim = 0;
        for (int a = 0; a < RegulatorCore.AXES; a++) for (int s = 0; s < RegulatorCore.ARMS; s++) {
            RegulatorCore.Motion m = core.comps[s][a];
            m.ph = planPhase[a][s];
            m.amp = planReach[a][s];
            phaseAngle[a][s] = planPhase[a][s];
            phaseInHand[a][s] = false;
        }
    }

    // ---- the selected component (the mod's docs/resonance_chamber.md §4.3) ----

    public int selectedAxis() {
        return axis;
    }

    public int selectedSlot() {
        return slot;
    }

    /** Whether the selected component exists: it has reach. (At the zero position it sits still, and is one all the same.) */
    public boolean selectedExists() {
        return exists(motion(axis, slot));
    }

    private static boolean exists(RegulatorCore.Motion m) {
        return m.eng && m.amp > RegulatorCore.ENGAGE_AMP;
    }

    /** The array's aim as a ratio. */
    public double aim() {
        return aim;
    }

    /**
     * Select a component (the toggle on a planted one, or the next arm's). What was selected is left where it
     * stands. A component there turns the aim to itself; an empty place leaves the aim alone.
     */
    public void select(int newAxis, int newSlot) {
        if (!has(newAxis, newSlot)) return;
        leave();
        arrive(newAxis, newSlot);
    }

    /**
     * The hand off the selected component for good: the crystal takes it if it stands in a station's window,
     * else it is held where it is. A bare aim (never given reach) is not a component and is not kept.
     */
    private void leave() {
        letGo();
        RegulatorCore.Motion was = motion(axis, slot);
        if (was.eng && was.drv) core.latch();
        if (was.eng && !exists(was)) drop(was);
    }

    private void arrive(int newAxis, int newSlot) {
        axis = newAxis;
        slot = newSlot;
        RegulatorCore.Motion m = motion(axis, slot);
        if (exists(m)) aim = m.r;
        else if (m.eng) drop(m);
    }

    private static void drop(RegulatorCore.Motion m) {
        m.eng = false;
        m.act = false;
        m.drv = false;
        m.amp = 0;
    }

    /**
     * The motions were laid on the core from outside (the server's, while this machine is only watched): the
     * aim and the tilts follow what is there now.
     */
    public void resync() {
        for (int a = 0; a < RegulatorCore.AXES; a++) for (int s = 0; s < RegulatorCore.ARMS; s++) {
            if (!phaseInHand[a][s] && ((int) Math.round(phaseAngle[a][s])) % 4 != core.comps[s][a].ph) phaseAngle[a][s] = core.comps[s][a].ph;
        }
        inHand = false;
        RegulatorCore.Motion m = motion(axis, slot);
        if (exists(m)) aim = m.r;
    }

    /**
     * The toggle at the socket: the next arm is the aiming arm. Its component in progress is selected if it has
     * one, else the first empty place on its axis, and the aim carries over. With every place on the axis taken it
     * is the last component there, and the array turns to it.
     */
    public void nextAxis() {
        int a = (axis + 1) % RegulatorCore.AXES;
        select(a, freeSlot(a));
    }

    /** The first slot of an axis holding no component, else the last slot. */
    public int freeSlot(int a) {
        for (int s = 0; s < core.freeArms; s++) {
            if (!exists(motion(a, s))) return s;
        }
        return core.freeArms - 1;
    }

    /**
     * The toggle on the composite: the selected component is planted where it stands (onto its station, if it
     * stands in the window), and the next place on its axis is selected. The aim stays on what was planted.
     * False when there is no component to plant.
     */
    public boolean plant() {
        if (!selectedExists()) return false;
        leave();
        RegulatorCore.Motion planted = motion(axis, slot);
        if (planted.eng) aim = planted.r;
        slot = freeSlot(axis);
        RegulatorCore.Motion m = motion(axis, slot);
        if (m.eng && !exists(m)) drop(m);
        return true;
    }

    /** The selected component brought into being if it is not yet: at the aim, with no reach, in its plan's phase. */
    private RegulatorCore.Motion ensureSelected() {
        RegulatorCore.Motion m = motion(axis, slot);
        if (!m.eng) {
            m.eng = true;
            m.act = true;
            m.drv = false;
            m.r = Math.max(0, Math.min(AIM_MAX, aim));
            m.amp = 0;
            m.ph = planPhase[axis][slot];
            m.osc = m.r * core.drawRate() * core.tau;
            phaseAngle[axis][slot] = m.ph;
        }
        return m;
    }

    /** A tick of a verb on the aiming arm's crystal: the selected component's reach moves. At nothing it is gone. */
    public void reach(double delta) {
        if (!core.powered) return;
        RegulatorCore.Motion m = ensureSelected();
        m.amp = Math.max(0, Math.min(1, m.amp + delta));
        if (m.amp <= 1e-6) {   // gone, and the aim stays where it is
            if (inHand) letGo();
            drop(m);
        }
    }

    /** The selected component's reach, 0 when it does not exist. */
    public double selectedReach() {
        RegulatorCore.Motion m = motion(axis, slot);
        return m.eng ? m.amp : 0;
    }

    /** A tick of a verb on the selected component's figure: its phase turns, in quarters. The core sees the nearest. */
    public void phase(double deltaQuarters) {
        if (!core.powered) return;
        RegulatorCore.Motion m = ensureSelected();
        double p = phaseAngle[axis][slot] + deltaQuarters;
        p -= 4 * Math.floor(p / 4);
        phaseAngle[axis][slot] = p;
        phaseInHand[axis][slot] = true;
        m.ph = ((int) Math.round(p)) % 4;
    }

    /** The hand off the phase: inside a quarter's well it is drawn on over PHASE_WELL_TAU; the tick does the drawing. */
    public void phaseLetGo() {
        phaseInHand[axis][slot] = false;
    }

    /** A component's phase as drawn: its continuous tilt in quarters. */
    public double phaseAngle(int a, int s) {
        return phaseAngle[a][s];
    }

    /**
     * One component's own swing along its axis at machine time {@code tauAt}, in units of reach: its part of
     * the pen, with the tilt as it is drawn. What its figure at its station is traced with.
     */
    public double swing(int a, int s, double tauAt) {
        RegulatorCore.Motion m = motion(a, s);
        if (!m.eng) return 0;
        return m.amp * Math.min(1, m.r / 0.6) * Math.sin(m.osc + m.r * core.drawRate() * (tauAt - core.tau) + phaseAngle[a][s] * Math.PI / 2);
    }

    /** The aim taken in hand for winding: the selected component (brought into being at the aim if need be). */
    public boolean takeSelected() {
        if (!core.powered) return false;
        ensureSelected();
        return take(axis, slot);
    }

    // ---- the hand ----

    /** The ratio of the motion last in hand: what the crank reads. */
    public double ratio() {
        return core.crankRatio();
    }

    /** Take a motion in hand. One at a time: what was in hand before is left where it stands, beating if detuned. */
    public boolean take(int axis, int slot) {
        if (!has(axis, slot) || !core.powered) return false;
        if (this.axis != axis || this.slot != slot) {
            motion(this.axis, this.slot).drv = false;
        }
        RegulatorCore.Motion m = motion(axis, slot);
        if (!m.eng) {
            m.eng = true;
            m.r = 0;
            m.osc = 0;
            m.ph = planPhase[axis][slot];
            m.amp = planReach[axis][slot];
            phaseAngle[axis][slot] = m.ph;
        }
        m.act = true;
        m.drv = true;
        core.arm = slot;
        core.setBrake(false);
        core.loadCrank(m.r);
        core.slip = HELD_SLIP;
        this.axis = axis;
        this.slot = slot;
        inHand = true;
        aim = m.r;
        return true;
    }

    /** Move the ratio of the motion in hand: up for crescendo, down for diminuendo. */
    public void push(double deltaRatio) {
        if (!inHand) return;
        double r = Math.max(0, Math.min(AIM_MAX, core.crankRatio() + deltaRatio));
        core.vel = r / 2;
        core.slip = HELD_SLIP;
        motion(axis, slot).r = r;
        aim = r;
    }

    /**
     * The hand comes off. Inside a station's window the well draws the motion in and the crystal takes it;
     * anywhere else it hangs where it is. Under the first resonance the core's crank would run down to rest and
     * take a driven motion with it, so there the motion is held off the crank instead: nothing is switched off
     * but by its reach.
     */
    public void letGo() {
        if (!inHand) return;
        inHand = false;
        core.slip = 0;
        RegulatorCore.Motion m = motion(axis, slot);
        if (m.eng && m.drv && m.r < 1 && core.acceptable(m.r) < 0) m.drv = false;
    }

    /** The machine's tick, then what the crystal does with a motion the hand has left. */
    public void tick(double dt) {
        core.tick(dt);
        for (int a = 0; a < RegulatorCore.AXES; a++) for (int s = 0; s < RegulatorCore.ARMS; s++) {
            if (phaseInHand[a][s]) continue;
            double p = phaseAngle[a][s], q = Math.round(p);
            if (Math.abs(p - q) > 1e-9) {   // the well at the quarter: let go, the tilt is drawn on
                p += (q - p) * Math.min(1, dt / PHASE_WELL_TAU);
                if (Math.abs(p - q) < 2e-3) p = q;   // close enough to see no difference: settle exactly
                phaseAngle[a][s] = p - 4 * Math.floor(p / 4);
                core.comps[s][a].ph = ((int) Math.round(phaseAngle[a][s])) % 4;
            }
        }
        RegulatorCore.Motion m = motion(axis, slot);
        if (!m.eng || !m.drv) return;
        aim = m.r;   // wound, or drawn in by a well: the aim goes with it
        if (inHand) return;
        if (core.wellDepth() == 1) {
            core.latch();   // settled on a resonance: the crystal takes it (accept:<slot>:<axis>:<n>)
            aim = m.r;
        }
    }
}
