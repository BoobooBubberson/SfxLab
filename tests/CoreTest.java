import java.util.*;
import sfxlab.runtime.*;

/** RegulatorCore's checks: shape matching, the three crank models (classic, free, brake & wells), levers, events,
 *  signals, the pen and the blueprint. Runtime only (no GUI). Run by tests/run.sh; exits non-zero on a failure. */
public class CoreTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }
    static void run(RegulatorCore c, double secs) { for (int i = 0; i < secs * 60; i++) c.tick(1 / 60.0); }
    static RegulatorCore.Recipe rec(String id, RegulatorCore.Comp... cs) { return new RegulatorCore.Recipe(id, id, 3, "x", false, cs); }
    static RegulatorCore.Comp C(int a, int n, int p) { return new RegulatorCore.Comp(a, n, p, 1); }
    /** Engaged list straight from components, for matching tests. */
    static List<RegulatorCore.Eng> eng(Object[]... m) { List<RegulatorCore.Eng> out = new ArrayList<>(); int i = 0; for (Object[] x : m) out.add(new RegulatorCore.Eng(i++ % 3, (Integer) x[0], ((Number) x[1]).doubleValue(), (Integer) x[2], 1.0)); return out; }
    public static void main(String[] a) {
        RegulatorCore.Recipe fire = RegulatorCore.DEFAULT_RECIPES[0], cinder = RegulatorCore.DEFAULT_RECIPES[1], lance = RegulatorCore.DEFAULT_RECIPES[2], wisp = RegulatorCore.DEFAULT_RECIPES[3];

        // ---- matching and shape equivalence (§3.4)
        check("fire bolt exact", RegulatorCore.evaluate(fire, eng(new Object[]{0, 3, 1}, new Object[]{1, 2, 0})).exact);
        check("fire bolt: trace started a quarter later (X p0, Y p2) is the same figure", RegulatorCore.evaluate(fire, eng(new Object[]{0, 3, 0}, new Object[]{1, 2, 2})).exact);
        check("fire bolt: traced backwards (X p1, Y p2) is the same figure", RegulatorCore.evaluate(fire, eng(new Object[]{0, 3, 1}, new Object[]{1, 2, 2})).exact);
        check("fire bolt: 8 distinct phase sets", fire.variants().size() == 8);
        check("fire bolt is symmetric: its mirror (X p3, Y p0) counts", RegulatorCore.evaluate(fire, eng(new Object[]{0, 3, 3}, new Object[]{1, 2, 0})).exact);
        List<RegulatorCore.Eng> lanceOk = new ArrayList<>(), lanceMirror = new ArrayList<>();
        for (RegulatorCore.Comp c : lance.comps) { lanceOk.add(new RegulatorCore.Eng(0, c.axis(), c.n(), c.phase(), c.amp())); lanceMirror.add(new RegulatorCore.Eng(0, c.axis(), c.n(), c.axis() == 0 ? (c.phase() + 2) % 4 : c.phase(), c.amp())); }
        check("torch lance exact", RegulatorCore.evaluate(lance, lanceOk).exact);
        RegulatorCore.Eval lm = RegulatorCore.evaluate(lance, lanceMirror);
        check("torch lance mirrored on X is a wrong answer (score " + String.format(Locale.ROOT, "%.3f", lm.score) + ")", !lm.exact && lm.score < 1);
        RegulatorCore.Eval off = RegulatorCore.evaluate(fire, eng(new Object[]{0, 3.1, 1}, new Object[]{1, 2, 0}));
        check("ratio off by 0.1: score e^-0.5 averaged = " + String.format(Locale.ROOT, "%.4f", off.score), !off.exact && Math.abs(off.score - (Math.exp(-0.5) + 1) / 2) < 1e-9);
        RegulatorCore.Eval extra = RegulatorCore.evaluate(fire, eng(new Object[]{0, 3, 1}, new Object[]{1, 2, 0}, new Object[]{2, 1, 0}));
        check("an extra engaged motion: not exact, score ×0.6", !extra.exact && Math.abs(extra.score - 0.6) < 1e-9);
        check("wrong phase (Y p1, in no variant) halves that component", Math.abs(RegulatorCore.evaluate(fire, eng(new Object[]{0, 3, 1}, new Object[]{1, 2, 1})).score - 0.75) < 1e-9);
        check("wisp (X1 p0 Y2 p0) is not fire bolt", !RegulatorCore.evaluate(fire, eng(new Object[]{0, 1, 0}, new Object[]{1, 2, 0})).exact && RegulatorCore.evaluate(wisp, eng(new Object[]{0, 1, 0}, new Object[]{1, 2, 0})).exact);
        check("degeneracy: sin 3t vs cos 2t retraces", RegulatorCore.degenerate(rec("d", C(0, 3, 0), C(1, 2, 1))));
        check("degeneracy: the current recipes are all fine", !RegulatorCore.degenerate(fire) && !RegulatorCore.degenerate(cinder) && !RegulatorCore.degenerate(lance) && !RegulatorCore.degenerate(wisp));

        // ---- crank physics (§3.1)
        RegulatorCore c = new RegulatorCore(); c.classic = true; c.coupling = false;
        c.setTarget(fire); c.power(true);
        c.axisLever(0);                                    // arm 1 X: driven from rest
        check("a pulled lever starts at rest, crank at rest", c.comps[0][0].eng && c.comps[0][0].drv && c.comps[0][0].r == 0 && c.caught == 0);
        for (int i = 0; i < 31; i++) c.nudge(1, RegulatorCore.NUDGE_WHEEL);   // 31 notches: ratio 3.1 (friction during the slip brings it into 3's window; 3.0 would decay past it)
        check("31 scroll notches = ratio 3.10, slipping", Math.abs(c.crankRatio() - 3.1) < 1e-9 && c.slip > 0);
        run(c, 0.4);
        check("during slip the crank is not caught", c.caught == -1);
        run(c, 1.5);
        check("then it catches ×3 exactly and holds", c.caught == 3 && c.crankRatio() == 3.0 && c.comps[0][0].r == 3.0);
        run(c, 5);
        check("a caught crank holds indefinitely", c.caught == 3 && c.crankRatio() == 3.0);
        c.latch();
        check("latch while caught: held at the exact integer", c.comps[0][0].eng && !c.comps[0][0].drv && c.comps[0][0].r == 3.0);
        // held -> driven loads the crank
        c.nudge(-1, 1.25); run(c, 3);   // ratio 0.5: under the first resonance the brake takes it to rest
        check("crank brakes to rest in the dead zone", c.caught == 0 && c.crankRatio() == 0);
        c.axisLever(0);
        check("held -> driven with no others loads ×3 and is caught at once", c.comps[0][0].drv && c.crankRatio() == 3.0 && c.caught == 3);
        // coasting into a resonance: 4.5 is outside 4's window (0.04) so friction carries it down into 4
        c.nudge(1, (4.5 - 3) / 2); run(c, 0.6);
        check("ratio 4.5 is not caught", c.caught == -1);
        run(c, 1.5);
        check("friction coasts it into ×4", c.caught == 4 && c.crankRatio() == 4.0);
        // overshoot rule: a nudge past 5 then coasting lands on 5
        c.nudge(1, 0.65); run(c, 3);   // ratio 5.3 -> e^-0.09t
        check("overshoot to 5.3 winds down into ×5", c.caught == 5);
        // stop: bring to rest and latch switches the motion off
        c.nudge(-1, 2.4); run(c, 3);
        check("ratio 0.2 (under the first resonance) ramps down to rest", c.caught == 0 && c.crankRatio() == 0);
        c.latch();
        check("latching at rest switches the motion off", !c.comps[0][0].eng && c.comps[0][0].r == 0);
        // drag: slip 0.15 after release, catch afterwards
        c.axisLever(0); c.dragStart(); for (int i = 0; i < 20; i++) c.dragVelocity(1.0); c.dragEnd();
        check("drag smooths toward the measured speed (ratio " + String.format(Locale.ROOT, "%.3f", c.crankRatio()) + ")", Math.abs(c.crankRatio() - 2) < 0.01 && c.slip == RegulatorCore.SLIP_DRAG);
        run(c, 1);
        check("after a drag it catches ×2", c.caught == 2 && c.crankRatio() == 2.0);
        check("ratio capped at 8", Math.abs(new RegulatorCore() {{ classic = true; coupling = false; nudge(1, 100); }}.crankRatio() - 8) < 1e-9);

        // ---- lever state machine and ganging (§3.2)
        c = new RegulatorCore(); c.classic = true; c.coupling = false; c.setTarget(cinder); c.power(true);
        check("tier II: 3 arms, 2 motions each", cinder.arms() == 3 && cinder.motionsPerArm() == 2 && c.selectArm(2));
        c.selectArm(0); c.axisLever(0); c.axisLever(1);
        check("two motions on one arm at tier II", c.engagedCount(0) == 2 && c.drivenCount() == 2);
        check("a third motion on that arm is refused", !c.axisLever(2) && c.engagedCount(0) == 2);
        c.nudge(1, 0.5); run(c, 2);
        check("ganged: both driven motions follow the crank (×1)", c.caught == 1 && c.comps[0][0].r == 1 && c.comps[0][1].r == 1);
        c.phaseStep();
        check("phase dial turns the focused motion (the last lever pressed: Y), not its ganged partner", c.comps[0][0].ph == 0 && c.comps[0][1].ph == 1);
        c.setReach(0.5);
        check("reach applies to the focused motion", c.comps[0][0].amp == 1.0 && c.comps[0][1].amp == 0.5);
        check("shift-focus X, trim it; focusing an off axis is refused", c.focusAxis(0) && !c.focusAxis(2) && c.focused() == c.comps[0][0]);
        c.phaseStep(); c.setReach(0.6);
        check("...X now trimmed, Y untouched", c.comps[0][0].ph == 1 && c.comps[0][0].amp == 0.6 && c.comps[0][1].ph == 1 && c.comps[0][1].amp == 0.5);
        c.focusAxis(1);
        c.latch();
        c.selectArm(1); c.axisLever(0);
        check("a new motion while others are held starts at rest", c.comps[1][0].r == 0 && c.caught == 0 && c.comps[0][0].r == 1);
        c.nudge(1, 2.65); run(c, 3);   // 5.3, coasting into 5
        check("it catches ×5 alone", c.caught == 5 && c.comps[1][0].r == 5 && c.comps[0][0].r == 1);
        c.selectArm(0); c.axisLever(0);   // held ×1 rejoins while arm 2 X is driven: it gangs at the crank's ratio
        check("held -> driven with others driven joins at the crank's ratio (ganging)", c.comps[0][0].drv && c.comps[0][0].r == 5);

        // ---- the setpoint and the copy socket
        c = new RegulatorCore(); c.classic = true; c.coupling = false; c.setTarget(cinder); c.power(true);
        c.loadSetpoint(new Random(3)); c.tick(1 / 60.0);
        List<RegulatorCore.Snap> snap = c.snapshot();
        int wrongPh = 0; boolean jitOk = true;
        List<RegulatorCore.Snap> ideal = RegulatorCore.recipeSnapshot(cinder);
        for (int i = 0; i < snap.size(); i++) { double d = Math.abs(snap.get(i).r() - ideal.get(i).r()); if (d < 0.1 - 1e-9 || d > 0.2 + 1e-9) jitOk = false; if (snap.get(i).phase() != ideal.get(i).phase()) wrongPh++; }
        check("setpoint: 5 held motions, ratios off by 0.1..0.2, exactly one phase wrong, score " + String.format(Locale.ROOT, "%.3f", c.targetEval.score), snap.size() == 5 && jitOk && wrongPh == 1 && !c.targetEval.exact && c.targetEval.score > 0.3 && c.drivenCount() == 0);
        check("setpoint lays cinder onto arms within the tier", ideal.get(0).arm() == 0 && ideal.get(1).arm() == 0 && ideal.get(2).arm() == 1 && ideal.get(4).arm() == 2);

        // ---- events, signals, voice
        c = new RegulatorCore(); c.classic = true; c.coupling = false; c.setTarget(fire); c.power(true);
        c.axisLever(0); c.nudge(1, 1.55); run(c, 2); c.phaseStep(); c.latch();            // arm1 X: 3.1 coasts into ×3, p1
        c.selectArm(1); c.axisLever(1); c.nudge(1, 1.05); run(c, 2);                       // arm2 Y: 2.1 coasts into ×2, p0, driven
        List<String> ev = c.events();
        check("lock event when the target matches exactly (" + ev + ")", ev.contains("lock") && c.targetEval.exact && c.signals[RegulatorCore.S_SCORE] == 1);
        check("signals: arm1.ratio 3, arm2.ratio 2, pitch 7.02 / 0", c.signals[0] == 3 && c.signals[1] == 2 && Math.abs(c.pitch(0) - 7.0196) < 0.001 && c.pitch(1) == 0);
        check("signals: reach 1 each, radiance 0 (no Z), tension 3/8, drive 2/8, coherence 1", Math.abs(c.signals[3] - 1.0) < 1e-9 && c.signals[6] == 0 && Math.abs(c.signals[8] - 0.375) < 1e-9 && Math.abs(c.signals[9] - 0.25) < 1e-9 && c.signals[10] == 1);
        check("consonance of 3:2 = 2/5", Math.abs(c.signals[7] - 0.4) < 1e-9);
        c.nudge(1, RegulatorCore.NUDGE_WHEEL); c.tick(1 / 60.0);
        ev = c.events();
        check("nudging the driven motion off breaks the lock: unlock event", ev.contains("unlock") && !c.targetEval.exact && c.signals[10] < 1);
        run(c, 2);
        check("…and it re-catches ×2, locking again", c.events().contains("lock") && c.targetEval.exact);
        List<RegulatorCore.Snap> written = c.voice();
        check("voice writes the sigil and clears the machine", written != null && written.size() == 2 && c.snapshot().isEmpty() && c.events().contains("voice") && c.voiced().size() == 1);
        c.applySnapshot(written, RegulatorCore.SOCKET_JITTER, false, new Random(1)); c.tick(1 / 60.0);
        check("copy socket reads it back nearly right (score " + String.format(Locale.ROOT, "%.3f", c.targetEval.score) + ")", c.targetEval.score > 0.8 && !c.targetEval.exact);
        // discovery: the wisp is X1 p0 Y2 p0 (arm-agnostic, so both on arm 1 at tier I is fine)
        c = new RegulatorCore(); c.classic = true; c.coupling = false; c.setTarget(fire); c.power(true);
        c.axisLever(0); c.nudge(1, 0.5); run(c, 2); c.latch(); c.selectArm(1); c.axisLever(1); c.nudge(1, 1.05); run(c, 2);
        check("a secret sigil found by accident: discover event", c.events().contains("discover:wisp") && c.discovered.contains("wisp") && c.eval.get("wisp").exact && !c.targetEval.exact);
        check("near-miss flicker source: per-recipe scores available", c.eval.get("firebolt").score > 0 && c.eval.get("firebolt").score < 0.75);

        // ---- the pen: a changing ratio bends the trace instead of jumping it; a held integer aligns to the receiver
        RegulatorCore pc = new RegulatorCore(); pc.wells = false; pc.coupling = false;   // the free crank (the wheel goes both ways) pc.setTarget(fire); pc.power(true);
        pc.axisLever(0); pc.nudge(1, 1.5); for (int i = 0; i < 60; i++) pc.tick(1 / 60.0);
        double[] q0 = new double[3], q1 = new double[3]; double maxStep = 0;
        for (int i = 0; i < 300; i++) {   // spin it up and down while sampling the pen at 480 Hz
            pc.nudge(i % 40 < 20 ? 1 : -1, RegulatorCore.NUDGE_WHEEL);
            double t0 = pc.tau;
            pc.tick(1 / 60.0);
            for (int k = 0; k <= 8; k++) { pc.pen(t0 + (pc.tau - t0) * k / 8, k == 0 ? q0 : q1); if (k > 0) { maxStep = Math.max(maxStep, Math.hypot(q1[0] - q0[0], q1[1] - q0[1])); q0[0] = q1[0]; q0[1] = q1[1]; } }
        }
        System.out.printf(Locale.ROOT, "  largest pen step while cranking hard: %.4f of reach (ratio now %.2f)%n", maxStep, pc.crankRatio());
        check("pen stays continuous under a spinning crank (no phase jumps)", maxStep < 0.12);
        pc.nudge(1, (3.02 - pc.crankRatio()) / 2); pc.tick(1 / 60.0); pc.latch();
        for (int i = 0; i < 120; i++) pc.tick(1 / 60.0);
        double offs = pc.comps[0][0].osc - 3 * RegulatorCore.DRAW_RATE * pc.tau; offs -= 2 * Math.PI * Math.rint(offs / (2 * Math.PI));
        check("held ×3 eases into alignment with the receiver within 2 s (offset " + String.format(Locale.ROOT, "%.4f", offs) + ")", Math.abs(offs) < 0.01);

        // ---- figure and blueprint
        double[] p = new double[3];
        c.figurePoint(0, p);
        check("figure at t=0: X (×1 p0) 0, Y (×2 p0) 0", Math.abs(p[0]) < 1e-9 && Math.abs(p[1]) < 1e-9);
        c.figurePoint(Math.PI / 2, p);
        check("figure at t=π/2: X = sin(π/2), Y = sin(π) = 0", Math.abs(p[0] - 1.0) < 1e-9 && Math.abs(p[1]) < 1e-9);
        double[][] front = RegulatorCore.blueprint(lance, RegulatorCore.BP_FRONT), top = RegulatorCore.blueprint(lance, RegulatorCore.BP_TOP);
        double t1 = 1.0 / (RegulatorCore.BP_POINTS - 1) * Math.PI * 8;   // point 1
        double zx = 0.7 * Math.sin(3 * t1 + Math.PI / 2 - 0.0025 * (1 + 2 * 0.3) * t1) + 0.25 * Math.sin(6 * t1 + 3 * Math.PI / 2 + 0.0025 * (1 + 5 * 0.3) * t1);   // drift: even j negative, odd j positive
        check("blueprint top view: +Z toward the bottom (v = -Z)", Math.abs(top[1][1] + zx * Math.exp(-0.022 * t1)) < 1e-9 && front[1][0] == top[1][0]);
        check("blueprint damps over four cycles", Math.hypot(front[2400][0], front[2400][1]) < Math.hypot(front[300][0], front[300][1]) * 0.7);
        check("extent of lance = 1.4 (X: 1 + 0.4)", Math.abs(RegulatorCore.extent(lance) - 1.4) < 1e-9);
        // ---- the free crank (the second model): friction only, acceptance on latch
        c = new RegulatorCore(); c.wells = false; c.coupling = false; c.setTarget(fire); c.power(true);
        c.axisLever(0); c.nudge(1, 1.55); run(c, 0.6);
        check("free crank never catches: ratio 3.1 decays past 3 (now " + String.format(Locale.ROOT, "%.3f", c.crankRatio()) + ")", c.caught == -1 && c.crankRatio() < 3.0 && c.crankRatio() > 2.8);
        run(c, 3);
        check("...and keeps winding down", c.caught == -1 && c.crankRatio() < 2.3);
        check("acceptance windows narrow as 1/√n: ×1 " + String.format(Locale.ROOT, "%.3f", c.acceptWindow(1)) + " ×7 " + String.format(Locale.ROOT, "%.3f", c.acceptWindow(7)), Math.abs(c.acceptWindow(1) - 0.1) < 1e-9 && Math.abs(c.acceptWindow(7) - 0.1 / Math.sqrt(7)) < 1e-9);
        c.nudge(1, (3.03 - c.crankRatio()) / 2); c.tick(1 / 60.0);
        check("within the window: acceptable(3.03) = 3", c.acceptable(c.crankRatio()) == 3);
        c.events(); c.latch();
        List<String> ev2 = c.events();
        check("latch inside the window snaps to ×3 exactly and reports accept:0:0:3 (" + ev2 + ")", c.comps[0][0].r == 3.0 && !c.comps[0][0].drv && ev2.contains("accept:0:0:3"));
        c.selectArm(1); c.axisLever(1); c.nudge(1, 1.1); c.tick(1 / 60.0);   // 2.2: outside ×2's window (0.071)
        c.events(); c.latch();
        ev2 = c.events();
        check("latch outside the window holds it detuned and reports hold:1:1 (r " + String.format(Locale.ROOT, "%.3f", c.comps[1][1].r) + ")", Math.abs(c.comps[1][1].r - 2.2) < 0.02 && ev2.contains("hold:1:1") && !c.targetEval.exact);
        run(c, 0.1);
        check("detuned motion: coherence below 1, figure precesses", c.signals[RegulatorCore.S_COHERENCE] < 0.9);
        c.axisLever(1);   // held -> driven: the crank picks it up, free (not caught)
        check("re-driving a held motion loads its ratio without catching", c.comps[1][1].drv && Math.abs(c.crankRatio() - c.comps[1][1].r) < 1e-9 && c.caught == -1);
        c.nudge(-1, (c.crankRatio() - 2.02) / 2); c.tick(1 / 60.0); c.latch(); run(c, 0.1);
        check("accepted ×2 on the second try: lock", c.comps[1][1].r == 2.0 && c.targetEval.exact);
        // reach targets
        c.setReach(0.5); c.axisLever(1); c.setReach(0.5); c.latch(); run(c, 0.1);
        check("reach 0.5 against a target of 1: not exact (rtol 0.1), score dips", !c.targetEval.exact && c.targetEval.score < 1 && c.targetEval.score > 0.8);
        c.axisLever(1); c.setReach(0.92); c.latch(); run(c, 0.1);
        check("reach within rtol of the target: exact again", c.targetEval.exact);
        RegulatorCore.Recipe tight = new RegulatorCore.Recipe("t", "t", 1, "", false, new RegulatorCore.Comp(0, 3, 1, 0.7), new RegulatorCore.Comp(1, 2, 0, 1));
        tight.rtol = 0.05;
        check("a recipe's own rtol applies (reach 0.92 vs 1 fails at 0.05)", !RegulatorCore.evaluate(tight, c.engaged()).exact);
        check("setpoint reaches are the recipe's targets", RegulatorCore.recipeSnapshot(lance).get(1).amp() == 0.8);

        // ---- brake & wells (the default crank model)
        RegulatorCore w = new RegulatorCore(); w.setTarget(fire); w.power(true);
        check("brake & wells is the default, coupled levers on", w.wells && !w.classic && w.coupling);
        check("its acceptance window is constant: ×1 and ×7 both " + w.snapTol, w.acceptWindow(1) == w.snapTol && w.acceptWindow(7) == w.snapTol);
        w.axisLever(0);
        List<Double> steps = new ArrayList<>();
        for (int i = 0; i < 20; i++) { double r0 = w.crankRatio(); w.nudge(1, RegulatorCore.NUDGE_WHEEL); steps.add(w.crankRatio() - r0); }
        double smin = Collections.min(steps), smax = Collections.max(steps);
        check(String.format(Locale.ROOT, "wheel notches vary within ±30%% of 0.1 (%.3f .. %.3f)", smin, smax), smin >= 0.07 - 1e-9 && smax <= 0.13 + 1e-9 && smax - smin > 0.01);
        double up = w.crankRatio(); w.nudge(-1, RegulatorCore.NUDGE_WHEEL);
        check("the wheel only goes up", w.crankRatio() == up);
        w.vel = 3.3 / 2; run(w, 4);
        check("no friction: released outside a window it keeps its speed (" + String.format(Locale.ROOT, "%.4f", w.crankRatio()) + ")", Math.abs(w.crankRatio() - 3.3) < 1e-9 && w.wellDepth() == 0);
        check("...and the motion it drives sits detuned with it", Math.abs(w.comps[0][0].r - 3.3) < 1e-9);
        w.setBrake(true); w.tick(0.02);
        double tap = (3.3 - w.crankRatio()) / 0.02;
        check(String.format(Locale.ROOT, "a tap of the brake takes about 0.4 ratio/s (%.3f)", tap), tap > 0.39 && tap < 0.45);
        run(w, 1.3); double r1 = w.crankRatio(); w.tick(0.1);
        check(String.format(Locale.ROOT, "held past 1.2 s it bites 4× (%.3f in 0.1 s)", r1 - w.crankRatio()), Math.abs(r1 - w.crankRatio() - 0.16) < 0.01);
        w.setBrake(false); w.vel = 3.06 / 2; run(w, 3);
        check("let go inside the window, the crystal eases it onto ×3 exactly", w.crankRatio() == 3.0 && w.wellDepth() == 1);
        w.events(); w.latch();
        check("latched there: accepted at ×3", w.comps[0][0].r == 3.0 && w.events().contains("accept:0:0:3"));
        RegulatorCore z = new RegulatorCore(); z.setTarget(fire); z.power(true); z.axisLever(0); z.nudge(1, RegulatorCore.NUDGE_WHEEL);
        z.vel = 0.5 / 2; run(z, 4);
        check("under ×1 the dead zone still brings it to rest", z.crankRatio() == 0);

        // ---- the coupled-lever model (default, under brake & wells): levers are the readout of what trim and the crank act on
        RegulatorCore k = new RegulatorCore(); k.setTarget(cinder); k.power(true);
        k.axisLever(0); k.axisLever(1);
        check("levers down: both active at rest, nothing coupled yet", k.comps[0][0].act && k.comps[0][1].act && k.comps[0][0].r == 0 && !k.coupled() && k.activeCount() == 2);
        k.phaseStep(); k.setReach(0.6);
        check("trim reaches every lever that is down", k.comps[0][0].ph == 1 && k.comps[0][1].ph == 1 && k.comps[0][0].amp == 0.6 && k.comps[0][1].amp == 0.6);
        k.nudge(1, 0.5); k.tick(1 / 60.0);
        check("touching the crank couples it to both (ganged at the crank's ratio)", k.coupled() && k.comps[0][0].drv && k.comps[0][1].drv && Math.abs(k.comps[0][0].r - k.crankRatio()) < 1e-9 && k.comps[0][1].r == k.comps[0][0].r);
        run(k, 0.4); k.nudge(1, (1.02 - k.crankRatio()) / 2); k.tick(1 / 60.0);
        int st = k.latch();
        check("latch snaps both to ×1 and decouples without moving the levers", st == 0 && !k.coupled() && k.comps[0][0].r == 1 && k.comps[0][1].r == 1 && k.comps[0][0].act && k.comps[0][1].act && k.events().contains("accept:0:0:1"));
        k.axisLever(0);
        check("lever up parks X: it keeps ×1 and leaves the trim set", !k.comps[0][0].act && k.comps[0][0].eng && k.comps[0][0].r == 1 && k.trimmed().size() == 1 && k.trimmed().get(0) == k.comps[0][1]);
        k.phaseStep();
        check("...so the dial now turns only Y", k.comps[0][0].ph == 1 && k.comps[0][1].ph == 2);
        k.selectArm(1); k.axisLever(0);   // arm 2 X, fresh at rest, while arm 1 Y is active at ×1
        k.nudge(1, RegulatorCore.NUDGE_WHEEL); k.tick(1 / 60.0);
        check("coupling takes the slowest motion already turning (Y at 1), and the fresh one joins it", Math.abs(k.comps[0][1].r - 1.1) < 0.02 && Math.abs(k.comps[1][0].r - k.comps[0][1].r) < 1e-9);
        k.nudge(1, 1.0); k.tick(1 / 60.0); k.nudge(1, (3.02 - k.crankRatio()) / 2); k.tick(1 / 60.0); k.latch();
        check("both accepted at ×3 (Y was dragged up with the crank, as the model says)", k.comps[0][1].r == 3 && k.comps[1][0].r == 3 && !k.coupled());
        k.selectArm(0); k.axisLever(0);   // X parked at ×1 → active again; Y active at ×3
        k.nudge(1, RegulatorCore.NUDGE_FINE); k.tick(1 / 60.0);
        check("re-activating a parked motion and scrolling couples everyone at the slowest (×1)", Math.abs(k.comps[0][0].r - 1.02) < 0.02 && Math.abs(k.comps[0][1].r - 1.02) < 0.02 && Math.abs(k.comps[1][0].r - 1.02) < 0.02);
        k.selectArm(2); k.axisLever(2); k.axisLever(2);
        check("a lever raised at rest switches its motion off", !k.comps[2][2].eng && k.events().contains("stopped:2:2"));
        k.applySnapshot(RegulatorCore.recipeSnapshot(cinder), 0, false, new Random(1));
        check("setpoint motions arrive parked (levers up), nothing coupled", k.activeCount() == 0 && !k.coupled() && k.engaged().size() == 5);

        // ---- orb signals: a full-size circle vs a tight fast loop
        RegulatorCore o = new RegulatorCore(); o.coupling = false; o.setTarget(fire); o.power(true);
        o.comps[0][0].eng = true; o.comps[0][0].r = 1; o.comps[0][0].ph = 1;   // X cos, Y sin: a unit circle drawn once per receiver cycle
        o.comps[1][1].eng = true; o.comps[1][1].r = 1; o.comps[1][1].ph = 0;
        run(o, 1.5);
        double[] circle = o.signals.clone();
        System.out.printf(Locale.ROOT, "  circle: speed %.3f accel %.3f curl %.3f radius %.3f%n", circle[RegulatorCore.S_ORB_SPEED], circle[RegulatorCore.S_ORB_ACCEL], circle[RegulatorCore.S_ORB_CURL], circle[RegulatorCore.S_ORB_RADIUS]);
        check("circle: steady speed 0.5 (two motions, each half the peak), curl 0.25, radius 1", Math.abs(circle[RegulatorCore.S_ORB_SPEED] - 0.5) < 0.02 && Math.abs(circle[RegulatorCore.S_ORB_CURL] - 0.25) < 0.02 && Math.abs(circle[RegulatorCore.S_ORB_RADIUS] - 1) < 0.02);
        o.comps[0][0].r = 6; o.comps[0][0].amp = 0.25; o.comps[1][1].r = 6; o.comps[1][1].amp = 0.25;   // a small fast circle: same curl formula, quarter the radius
        for (int i = 0; i < 90; i++) o.tick(1 / 60.0);
        System.out.printf(Locale.ROOT, "  small fast circle: speed %.3f accel %.3f curl %.3f radius %.3f%n", o.signals[RegulatorCore.S_ORB_SPEED], o.signals[RegulatorCore.S_ORB_ACCEL], o.signals[RegulatorCore.S_ORB_CURL], o.signals[RegulatorCore.S_ORB_RADIUS]);
        check("tight loop: curl climbs (0.7 with the extent floored at 0.7) while radius falls", o.signals[RegulatorCore.S_ORB_CURL] > 0.6 && o.signals[RegulatorCore.S_ORB_RADIUS] < 0.4);
        o.comps[0][0].r = 1; o.comps[0][0].amp = 1; o.comps[1][1].r = 5; o.comps[1][1].amp = 0.3;   // a wide sweep with fast wiggles: curl and speed pulse
        double lo = 2, hi = 0;
        for (int i = 0; i < 300; i++) { o.tick(1 / 60.0); if (i > 60) { lo = Math.min(lo, o.signals[RegulatorCore.S_ORB_CURL]); hi = Math.max(hi, o.signals[RegulatorCore.S_ORB_CURL]); } }
        System.out.printf(Locale.ROOT, "  wide sweep with wiggles: curl pulses %.3f .. %.3f%n", lo, hi);
        check("orb signals move with the figure (curl pulses)", hi - lo > 0.1);
        RegulatorCore q = new RegulatorCore(); q.setTarget(fire); q.power(true);
        run(q, 0.5);
        check("stir: 0 with nothing engaged", q.signals[RegulatorCore.S_STIR] == 0);
        q.axisLever(0); run(q, 0.5);
        check("stir: still 0 with a lever down at rest", q.signals[RegulatorCore.S_STIR] < 0.01);
        q.nudge(1, 0.6); run(q, 0.6);
        check("stir: 1 once the motion turns (ratio " + String.format(Locale.ROOT, "%.2f", q.crankRatio()) + ")", q.signals[RegulatorCore.S_STIR] > 0.95);
        q.latch(); q.axisLever(0); run(q, 1);   // accepted ×1 and parked: still turning without the crank
        check("stir: stays 1 for a parked motion (nothing coupled to the crank)", q.signals[RegulatorCore.S_STIR] > 0.95 && !q.coupled());
        check("one signal list: the bench reads the core's, with a range for every signal", Sfx.SIGNALS == RegulatorCore.SIGNALS && Sfx.SIG_MAX.length == RegulatorCore.SIGNALS.length);

        // ---- the free machine (the game's): no pin, the machine's own limits, the score follows the best recipe
        RegulatorCore f = new RegulatorCore(); f.classic = true; f.coupling = false; f.setFree(3, 3); f.power(true);
        check("free: three arms of three motions whatever is pinned (the default pin is tier I)", f.target == fire && f.arms() == 3 && f.motionsPerArm() == 3 && f.selectArm(2));
        f.selectArm(0); boolean three = f.axisLever(0) & f.axisLever(1) & f.axisLever(2);
        check("free: one arm takes X, Y and Z", three && f.engagedCount(0) == 3);
        f.setFree(1, 2);
        check("free: a smaller machine refuses what it cannot make (1 arm × 2 motions), and setFree cleared the arms", f.engaged().isEmpty() && !f.selectArm(1) && f.axisLever(0) && f.axisLever(1) && !f.axisLever(2));
        f.setFree(3, 3); f.target = lance; f.events();
        f.selectArm(0); f.axisLever(0); f.nudge(1, 0.5); run(f, 2); f.latch();            // X ×1, p0
        f.selectArm(1); f.axisLever(1); f.nudge(1, 1.05); run(f, 2);                       // Y: 2.1 coasts into ×2, p0
        run(f, 0.1);
        List<String> fev = f.events();
        check("free: wisp (secret, not the pin) held exactly: match, lock and discover, never wrong (" + fev + ")", fev.contains("match:wisp") && fev.contains("lock") && fev.contains("discover:wisp") && fev.stream().noneMatch(e -> e.startsWith("wrong")));
        check("free: matched and best are wisp, the score and fit are its own", f.matched == wisp && f.best == wisp && f.targetEval.exact && f.signals[RegulatorCore.S_SCORE] == 1 && f.signals[RegulatorCore.S_FIT] > 0.99);
        f.latch(); f.selectArm(0); f.axisLever(0);                                        // Y held at ×2; X held → driven alone, the crank loads ×1
        f.nudge(1, 1.05); run(f, 2); f.phaseStep(); run(f, 0.1);                           // X: 3.1 coasts into ×3, p1: fire bolt
        fev = f.events();
        check("free: retuned to fire bolt: wisp unmatched, fire bolt matched (" + fev + ")", fev.contains("unmatch:wisp") && fev.contains("unlock") && fev.contains("match:firebolt") && fev.lastIndexOf("lock") > fev.lastIndexOf("unlock") && f.matched == fire);
        f.nudge(1, 0.1); f.tick(1 / 60.0);
        fev = f.events();
        check("free: off the resonance: unlock, nothing matched, the score still follows fire bolt (" + String.format(Locale.ROOT, "%.3f", f.targetEval.score) + ")", fev.contains("unlock") && f.matched == null && f.best == fire && f.targetEval.score > 0.5 && f.targetEval.score < 1);
        check("free: nothing to voice off the match", f.voice() == null);
        run(f, 2);
        check("free: back in the resonance, the voice lever writes fire bolt and clears the arms", f.matched == fire && f.voice() != null && f.lastVoiced == fire && f.engaged().isEmpty() && f.events().contains("voice"));
        f.tick(1 / 60.0);
        check("free: the cleared machine unlocks", f.events().contains("unlock") && f.matched == null && f.best == null && f.targetEval.score == 0);
        f.comps[0][0].eng = true; f.comps[0][0].r = 3; f.comps[0][0].ph = 1; f.comps[1][1].eng = true; f.comps[1][1].r = 2; f.power(false); f.tick(1 / 60.0);
        check("free: unpowered, an exact figure is no match and cannot be voiced", f.matched == null && !f.events().contains("lock") && f.voice() == null);
        check("exactMatch: a host's check of claimed motions", RegulatorCore.exactMatch(RegulatorCore.DEFAULT_RECIPES, eng(new Object[]{0, 3, 1}, new Object[]{1, 2, 0})) == fire
                && RegulatorCore.exactMatch(RegulatorCore.DEFAULT_RECIPES, eng(new Object[]{0, 3.01, 1}, new Object[]{1, 2, 0})) == null);
        RegulatorCore pin = new RegulatorCore(); pin.classic = true; pin.coupling = false; pin.setTarget(fire); pin.power(true);
        check("pinned (the default) is untouched: tier I limits, no best / matched", !pin.free && pin.arms() == 2 && pin.motionsPerArm() == 1 && !pin.selectArm(2) && pin.axisLever(0) && !pin.axisLever(1) && pin.best == null && pin.matched == null);

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
