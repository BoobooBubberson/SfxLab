import java.util.*;
import sfxlab.runtime.*;

/** The conducted machine on its own (runtime only): the verbs' curves, what moves the aim and what does not, the
 *  ends of the arc, and every default recipe built by the casts alone. The mod runs fuller checks against the
 *  shipped families; these keep the class honest where it lives. */
public class ConductedTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }
    static final double DT = 0.05;
    static ConductedMachine machine() { ConductedMachine m = new ConductedMachine(RegulatorCore.DEFAULT_RECIPES, 3, 3); m.core.power(true); return m; }
    static void rest(ConductedMachine m, double s) { for (int i = 0; i < s / DT; i++) m.tick(DT); }
    static void reachTo(ConductedMachine m, double want) {
        double h = 0;
        while (m.selectedReach() < want - 1e-9) { h += DT; m.reach(Math.min(want - m.selectedReach(), ConductedMachine.CRESCENDO.reach(h, DT))); m.tick(DT); }
    }
    /** Crescendo past, diminuendo back into the window, let go: the crystal takes it. */
    static void aimTo(ConductedMachine m, int n) {
        if (!m.takeSelected()) return;
        double h = 0;
        while (m.aim() < n + 0.2) { h += DT; m.push(ConductedMachine.CRESCENDO.aim(h, DT)); m.tick(DT); }
        h = 0;
        while (m.aim() > n + 0.05) { h += DT; m.push(Math.max(n + 0.03 - m.aim(), ConductedMachine.DIMINUENDO.aim(h, DT))); m.tick(DT); }
        m.letGo();
        rest(m, 6);
    }

    public static void main(String[] a) {
        ConductedMachine.Verb up = ConductedMachine.CRESCENDO, down = ConductedMachine.DIMINUENDO;
        double pulse = 0, t = 0;
        for (int i = 0; i < 5; i++) { t += DT; pulse -= down.aim(t, DT); }
        check("crescendo is steady and up, diminuendo starts gently and quickens", up.aim(0, 1) == up.aim(5, 1) && up.aim(0, 1) > 0 && down.aim(0, 1) < 0 && down.aim(2, 1) < 5 * down.aim(0, 1) && pulse < 0.03);

        ConductedMachine m = machine();
        reachTo(m, 1);
        check("reach at the zero position is a component, sitting still", m.selectedExists() && m.core.engaged().isEmpty() && m.aim() == 0);
        aimTo(m, 3);
        check("the aim wound past ×3 and damped back is taken by the crystal (" + m.aim() + ")", m.aim() == 3 && m.motion(0, 0).r == 3 && !m.motion(0, 0).drv);
        ConductedMachine quick = machine();
        reachTo(quick, 1);
        quick.takeSelected();
        while (quick.aim() < 2.0) { quick.push(up.aim(1, DT)); quick.tick(DT); }
        quick.push(2.09 - quick.aim()); quick.tick(DT);   // just inside the window's edge
        quick.letGo();
        int ticks = 0;
        while (quick.motion(0, 0).drv && ticks < 200) { quick.tick(DT); ticks++; }
        check("let go at the window's edge, the crystal takes it in about a second (" + String.format(Locale.ROOT, "%.2f s", ticks * DT) + "), exactly", quick.motion(0, 0).r == 2 && ticks * DT > 0.5 && ticks * DT < 1.5);
        for (int i = 0; i < 60; i++) { m.reach(-0.4 * DT); m.tick(DT); }
        rest(m, 2);
        check("a component shrunk to nothing is gone, and the aim has not moved", !m.motion(0, 0).eng && m.aim() == 3);

        m = machine();
        reachTo(m, 1);
        m.takeSelected();
        for (int i = 0; i < 400; i++) { m.push(up.aim(1, DT)); m.tick(DT); }
        m.letGo(); rest(m, 6);
        boolean accepted = false;
        for (String ev : m.core.events()) if (ev.startsWith("accept")) accepted = true;
        check("the aim stops at ×" + ConductedMachine.AIM_MAX + ": the last station can be overshot, ×8 never reached", m.aim() == ConductedMachine.AIM_MAX && !accepted);
        m.takeSelected();
        for (int i = 0; i < 400 && m.aim() > 0.5; i++) { m.push(down.aim(i * DT, DT)); m.tick(DT); }
        m.letGo(); rest(m, 4);
        check("let go under the first station it hangs where it is: the machine switches nothing off (" + m.aim() + ")", m.motion(0, 0).eng && m.aim() > 0.2 && m.aim() <= 0.5);

        m = machine();
        m.takeSelected(); aimTo(m, 4);
        m.nextAxis();
        check("a bare aim is not a component: it is not kept, and the aim is shared with the next arm", !m.motion(0, 0).eng && m.aim() == 4 && m.selectedAxis() == 1);

        for (RegulatorCore.Recipe rec : RegulatorCore.DEFAULT_RECIPES) {
            m = machine();
            int[] next = new int[3];
            for (RegulatorCore.Comp c : rec.comps) {
                while (m.selectedAxis() != c.axis()) m.nextAxis();
                reachTo(m, c.amp());
                if (!(m.aim() == c.n())) {
                    if (m.aim() > c.n()) { m.takeSelected(); double h = 0; while (m.aim() > c.n() + 0.05) { h += DT; m.push(Math.max(c.n() + 0.03 - m.aim(), down.aim(h, DT))); m.tick(DT); } m.letGo(); rest(m, 6); }
                    else aimTo(m, c.n());
                }
                double h = 0;
                while ((((c.phase() - m.phaseAngle(m.selectedAxis(), m.selectedSlot())) % 4) + 4) % 4 > 0.2 && h < 10) { h += DT; m.phase(up.phase(h, DT)); m.tick(DT); }
                m.phaseLetGo(); rest(m, 2);
                m.plant();
            }
            rest(m, 0.5);
            check("the casts alone build " + rec.name, m.core.matched == rec);
        }
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
