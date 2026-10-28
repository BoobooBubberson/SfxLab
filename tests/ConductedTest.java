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
        m.reachLetGo();
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
        // nothing is erased but by the hand (2026-10-28; before: planting was the commit and every leaving discarded
        // what was not planted): a take-back leaves a component standing; the socket press discards what the hand has
        // not locked; the press on the composite is the lock; one motion to an axis at a station
        m = machine();
        reachTo(m, 1); aimTo(m, 3);
        m.nextAxis();
        check("the next arm discards a component caught on its station but not locked, and the aim is shared", !m.motion(0, 0).eng && m.selectedAxis() == 1 && m.aim() == 3);
        m.nextAxis(); m.nextAxis();
        reachTo(m, 1); aimTo(m, 3); m.plant();
        m.nextAxis();
        check("a locked component is kept by the next arm", m.motion(0, 0).eng && m.motion(0, 0).r == 3 && m.locked(0, 0) && m.standing(0, 0));
        m.nextAxis(); m.nextAxis();
        m.select(0, 0);
        check("taken back it is in hand, the aim on it", !m.standing(0, 0) && m.selectedAxis() == 0 && m.aim() == 3);
        m.nextAxis();
        check("and the next arm leaves it standing again, still locked", m.motion(0, 0).eng && m.motion(0, 0).r == 3 && m.standing(0, 0) && m.locked(0, 0));
        reachTo(m, 0.5); aimTo(m, 5);
        m.select(0, 0);
        check("a take-back leaves a component not locked standing (" + m.motion(1, 0).r + ")", m.motion(1, 0).eng && m.motion(1, 0).r == 5 && m.standing(1, 0) && !m.locked(1, 0));
        m = machine();
        reachTo(m, 1); m.takeSelected();
        { double h = 0; while (m.aim() < 2.6) { h += DT; m.push(up.aim(h, DT)); m.tick(DT); } }
        m.letGo(); rest(m, 3);
        m.plant();
        check("a component let go loose between stations is locked there too, loose (" + m.motion(0, 0).r + ")", m.motion(0, 0).eng && m.motion(0, 0).r > 2.2 && m.motion(0, 0).r < 2.8 && m.standing(0, 0) && m.locked(0, 0));
        m = machine();
        reachTo(m, 1); aimTo(m, 2); m.plant();
        reachTo(m, 0.5); m.plant();
        check("a second X planted on a station the first holds locked gives way: the locked one keeps it, in hand (one motion to an axis at a station)",
                m.motion(0, 0).eng && !m.motion(0, 1).eng && m.motion(0, 0).amp == 1 && m.locked(0, 0) && m.selectedSlot() == 0);
        m = machine();
        m.nextAxis(); reachTo(m, 1); aimTo(m, 4); m.plant();   // Y locked on ×4
        m.nextAxis(); m.nextAxis();                              // round to X
        reachTo(m, 1); aimTo(m, 2);                              // X caught on ×2, never locked
        m.select(1, 0);                                          // the take-back of Y leaves it standing unlocked
        m.nextAxis(); m.nextAxis();                              // Y (locked, kept), Z, X: the next place on X
        check("left standing by a take-back, unlocked", m.selectedAxis() == 0 && m.selectedSlot() == 1 && m.motion(0, 0).eng && !m.locked(0, 0));
        reachTo(m, 0.5); aimTo(m, 2);
        check("a component caught on a station its axis holds unlocked replaces the earlier one",
                !m.motion(0, 0).eng && m.motion(0, 1).eng && m.motion(0, 1).r == 2 && m.motion(0, 1).amp == 0.5);
        // the lock: the press on the composite fixes the ratio; taken back, winding leaves it and the arm winds a bare aim
        m = machine();
        reachTo(m, 0.7); aimTo(m, 3); m.plant();
        check("the press locks it, standing, and the next place is selected", m.locked(0, 0) && m.standing(0, 0) && !m.selectedExists() && m.aim() == 3);
        m.select(0, 0);
        check("taken back it is in hand and still locked", !m.standing(0, 0) && m.locked(0, 0) && m.selectedSlot() == 0);
        { double h = 0; while (m.selectedReach() > 0.5 + 1e-9) { h += DT; m.reach(Math.max(0.5 - m.selectedReach(), down.reach(h, DT))); m.tick(DT); } }
        m.reachLetGo(); rest(m, 1);
        check("its reach is the hand's to change in place", m.motion(0, 0).amp == 0.5 && m.motion(0, 0).r == 3 && m.locked(0, 0));
        m.takeSelected();
        { double h = 0; while (m.aim() < 5.2) { h += DT; m.push(up.aim(h, DT)); m.tick(DT); } }
        check("wound, the locked component stays on its station and the arm carries a bare aim away (" + m.aim() + ")",
                m.motion(0, 0).r == 3 && m.locked(0, 0) && m.standing(0, 0) && m.selectedSlot() == 1 && !m.selectedExists() && m.aim() > 5);
        m.letGo(); rest(m, 2);
        m = machine();
        reachTo(m, 0.7); aimTo(m, 3);
        m.takeSelected();
        { double h = 0; while (m.aim() < 4.2) { h += DT; m.push(up.aim(h, DT)); m.tick(DT); } }
        check("not yet locked, the component goes with the wind (" + m.aim() + ")", m.selectedSlot() == 0 && m.selectedExists() && m.motion(0, 0).r > 4);
        m.letGo(); rest(m, 3);
        m = machine();
        reachTo(m, 0.7); aimTo(m, 3); m.plant();
        reachTo(m, 0.4); aimTo(m, 3);
        check("a provisional component caught on a station a locked one of its axis holds gives way, and the arm holds the locked one",
                m.locked(0, 0) && m.motion(0, 0).amp == 0.7 && m.selectedSlot() == 0 && !m.motion(0, 1).eng && m.core.engaged().size() == 1);
        m.nextAxis(); m.nextAxis(); m.nextAxis();
        reachTo(m, 0.4); m.takeSelected();
        { double h = 0; while (m.aim() < 4.2) { h += DT; m.push(up.aim(h, DT)); m.tick(DT); } }
        { double h = 0; while (m.aim() > 3.05) { h += DT; m.push(Math.max(3.04 - m.aim(), down.aim(h, DT))); m.tick(DT); } }
        m.plant();
        check("planted onto it the same: the lock is never erased but by its reach", m.locked(0, 0) && m.motion(0, 0).amp == 0.7 && m.core.engaged().size() == 1 && m.selectedSlot() == 0);
        { double h = 0; while (m.motion(0, 0).eng) { h += DT; m.reach(down.reach(h, DT)); m.tick(DT); } }
        check("diminuendo on its reach to nothing is the one eraser", !m.motion(0, 0).eng && !m.locked(0, 0) && m.aim() == 3);
        // the grab: the aiming arm wound with nothing in hand and let go near one of its own standing components takes it
        m = machine();
        reachTo(m, 0.7); aimTo(m, 3); m.plant();
        m.nextAxis(); m.nextAxis(); m.nextAxis();   // round to X again, a bare aim at ×3's station
        check("after the release a bare aim stands at the station, nothing in hand", m.selectedAxis() == 0 && !m.selectedExists() && m.aim() == 3);
        m.takeSelected();
        { double h = 0; while (m.aim() < 5.2) { h += DT; m.push(up.aim(h, DT)); m.tick(DT); } }
        check("held, the bare aim passes through its own station without taking it", m.selectedExists() == false && m.standing(0, 0) && m.motion(0, 0).r == 3 && m.aim() > 5);
        { double h = 0; while (m.aim() > 3.15) { h += DT; m.push(Math.max(3.14 - m.aim(), down.aim(h, DT))); m.tick(DT); } }
        m.letGo();
        int gticks = 0;
        while (!(m.selectedSlot() == 0 && m.selectedExists()) && gticks < 200) { m.tick(DT); gticks++; }
        check("let go within the grab's window (wider than the catch's) it is drawn in and the component is in hand (" + String.format(Locale.ROOT, "%.2f s", gticks * DT) + ")",
                m.selectedAxis() == 0 && m.selectedSlot() == 0 && m.selectedExists() && !m.standing(0, 0) && m.locked(0, 0) && m.aim() == 3 && m.motion(0, 0).amp == 0.7);
        rest(m, 1);
        boolean reaccepted = false;
        for (String ev : m.core.events()) if (ev.startsWith("accept:0:0:3")) reaccepted = true;
        check("the crystal takes it again, held exactly on its station", reaccepted && m.motion(0, 0).r == 3 && !m.motion(0, 0).drv);
        m = machine();
        reachTo(m, 0.7); aimTo(m, 3); m.plant(); m.nextAxis();
        m.takeSelected();
        { double h = 0; while (m.aim() < 3.5) { h += DT; m.push(up.aim(h, DT)); m.tick(DT); } }
        { double h = 0; while (m.aim() > 3.15) { h += DT; m.push(Math.max(3.14 - m.aim(), down.aim(h, DT))); m.tick(DT); } }
        m.letGo(); rest(m, 3);
        check("another axis's bare aim is not grabbed by X's component: it hangs outside the catch's window (" + m.aim() + ")", m.selectedAxis() == 1 && !m.selectedExists() && m.aim() > 3.1 && m.standing(0, 0));
        // the reach well: let go, a reach is drawn onto the nearest tenth; below half a step it is nothing
        m = machine();
        reachTo(m, 0.63); m.reachLetGo(); rest(m, 2);
        check("a reach let go at 0.63 is drawn onto 0.6", m.selectedReach() == 0.6);
        { double h = 0; while (m.selectedReach() > 0.04) { h += DT; m.reach(down.reach(h, DT)); m.tick(DT); } }
        check("under the hand the reach is not drawn (" + m.selectedReach() + ")", m.selectedReach() > 0.02 && m.selectedExists() == false || m.selectedReach() > 0.02);
        m.reachLetGo(); rest(m, 2);
        check("let go under half a step it is gone", !m.motion(0, 0).eng);
        check("a verb's reach is steady, its own rate, up and down", up.reach(0, DT) == up.reach(5, DT) && up.reach(0, DT) > 0 && down.reach(0, DT) == -up.reach(0, DT) && down.reach(3, DT) == down.reach(0, DT));
        ConductedMachine hm = machine();
        hm.tick(DT);
        check("hand.fit: nothing in hand reads 0", hm.core.signals[RegulatorCore.S_HAND_FIT] == 0 && hm.core.handArm == -1);
        hm.reach(0.3); hm.tick(DT);
        check("hand.fit: a component given reach is the one in hand", hm.selectedExists() && hm.core.handArm == hm.selectedSlot() && hm.core.handAxis == hm.selectedAxis());
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
