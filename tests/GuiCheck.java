import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import sfxlab.runtime.*;

/** Headless checks of the authoring GUI around the shared runtime: the regulator panel's bind tables (mutes, filter,
 *  undo) and the machine window playing to a lock and voicing the crystal. Loads the pyretic family without assuming
 *  its contents, never autosaves, and leaves two screenshots in the folder given as the first argument. */
public class GuiCheck {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }
    static void layoutAll(Component c) { c.doLayout(); if (c instanceof Container ct) for (Component k : ct.getComponents()) layoutAll(k); }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sfxlab.noautosave", "true");
        Path shots = Paths.get(a.length > 0 ? a[0] : ".");
        Files.createDirectories(shots);
        SfxLab lab = new SfxLab();
        lab.benchOn = true;
        lab.loadFamily("pyretic");
        String disk = String.join("\n", Files.readAllLines(SfxLab.familyFile("pyretic"))) + "\n";
        check("family loads: " + lab.bench.layers.size() + " layers, " + lab.bench.binds.size() + " binds, " + lab.mix.spells.size() + " spells",
              !lab.bench.layers.isEmpty() && !lab.bench.binds.isEmpty() && !lab.mix.spells.isEmpty());
        check("the family text round-trips to the file on disk", lab.familyText().equals(disk));

        // a live absolute bind on a layer of the palette, whatever the family holds
        Bind bd = null; Clip lay = null; int pi = -1;
        for (Bind b : lab.bench.binds) {
            Clip c = lab.bench.byId(b.layer);
            if (c != null && !b.rel && !b.mute && Sfx.sigIdx(b.sig) >= 0 && Sfx.idxOf(c.type, b.param) >= 0) { bd = b; lay = c; pi = Sfx.idxOf(c.type, b.param); break; }
        }
        check("the palette has an absolute bind to exercise", bd != null);
        if (bd != null) {
            int si = Sfx.sigIdx(bd.sig);
            double top = Sfx.SIG_MAX[si];
            lab.setSignal(si, top);
            double[] m = new double[lay.p.length];
            lab.applyBinds(List.of(bd), lay, m);
            double[] lh = lab.bindRange(bd, lay, pi);
            check("bind " + bd.line() + " at full signal lands on its hi", Math.abs(lay.p[pi] + m[pi] - bd.map(lh[1], lh[0], lh[1])) < 1e-9);
            lab.mutedSigs.add(bd.sig);
            m = new double[lay.p.length]; lab.applyBinds(List.of(bd), lay, m);
            check("a muted signal holds its binds still", m[pi] == 0);
            lab.mutedSigs.remove(bd.sig);
            bd.mute = true;
            m = new double[lay.p.length]; lab.applyBinds(List.of(bd), lay, m);
            Bench rt = SfxFormat.parseBench(List.of(bd.line()));
            check("a bind switched off contributes nothing and re-parses as off", m[pi] == 0 && rt.binds.get(0).mute);
            bd.mute = false;
            lab.setSignal(si, 0);
        }

        // the panel: one bind table per section, the filter, the mute column, undo over spells
        SfxLab.BenchPanel bp = new SfxLab.BenchPanel(lab);
        bp.rebuildSpells(); bp.refresh();
        check("bind tables: the palette plus one per spell (" + bp.bindModels.size() + ")", bp.bindModels.size() == 1 + lab.mix.spells.size());
        int all = 0; for (SfxLab.BenchPanel.BindModel md : bp.bindModels) all += md.getRowCount();
        bp.filterF.setText("zzz-no-such-signal");
        int shown = 0; for (SfxLab.BenchPanel.BindModel md : bp.bindModels) shown += md.sorter.getViewRowCount();
        check("the filter hides rows that do not match (" + shown + " of " + all + ")", shown == 0 && all > 0);
        bp.filterF.setText("");
        SfxLab.BenchPanel.BindModel pm = bp.bindModels.get(0);
        pm.setValueAt(Boolean.FALSE, 0, 1);   // columns: #, on, signal, layer, param, ...
        check("unticking a row's on box mutes that bind", lab.bench.binds.get(0).mute);
        pm.setValueAt(Boolean.TRUE, 0, 1);
        int n = lab.mix.spells.size();
        lab.pushUndo(""); ArrayList<Spell> out = new ArrayList<>(lab.mix.spells); out.remove(0); lab.setSpells(out);
        lab.benchUndo();
        check("undo brings back a removed spell", lab.mix.spells.size() == n);
        check("the family text is unchanged by all of it", lab.familyText().equals(disk));

        // shift+M: the mono audition (the game's one positional source), remembered in lab.cfg (the test workspace's)
        boolean mono0 = lab.monoOut;
        java.awt.event.KeyEvent sm = new java.awt.event.KeyEvent(lab, java.awt.event.KeyEvent.KEY_PRESSED, 0, java.awt.event.KeyEvent.SHIFT_DOWN_MASK, java.awt.event.KeyEvent.VK_M, 'M');
        lab.handleKey(sm);
        boolean saved = Files.readString(SfxLab.CFG_FILE).contains("mono_out=" + (lab.monoOut ? 1 : 0));
        check("shift+M toggles the mono audition and remembers it", lab.monoOut != mono0 && saved);
        lab.handleKey(sm);

        // the machine: power, drive the bench, lock, freeze, voice
        SfxLab.Machine mc = new SfxLab.Machine(lab);
        mc.setSize(1180, 720); mc.addNotify(); layoutAll(mc); layoutAll(mc);
        RegulatorCore c = mc.core;
        check("the machine opens on the brake & wells crank", c.wells && !c.classic);
        c.wells = false; c.classic = true; mc.classicB.setSelected(true); c.coupling = false; mc.couplingB.setSelected(false);   // scripted below with the catching crank
        RegulatorCore.Recipe target = null;
        for (RegulatorCore.Recipe r : c.recipes) if (r.comps.length == 2) { target = r; break; }
        check("the family has a two-motion recipe to play", target != null);
        if (target == null) { System.out.println(fails + " FAILED"); System.exit(1); }
        c.setTarget(target); mc.syncTarget();
        mc.power.doClick();
        check("power starts the bench playing", c.powered && lab.benchPlaying);
        for (RegulatorCore.Comp k : target.comps) {   // each motion on its own arm, spun to its ratio, phased, reached, latched
            int arm = Arrays.asList(target.comps).indexOf(k);
            c.selectArm(arm); c.axisLever(k.axis());
            c.loadCrank(k.n());                        // the crank on the integer, caught
            for (int i = 0; i < 30; i++) c.tick(1 / 60.0);
            while (c.focused().ph != k.phase()) c.phaseStep();
            c.setReach(k.amp());
            c.latch();
        }
        for (int i = 0; i < 30; i++) { c.tick(1 / 60.0); mc.frameTick(); }
        check("lock reached and handed to the bench (score " + lab.sigVal[Sfx.SIG_SCORE] + ")", c.targetEval.exact && lab.sigVal[Sfx.SIG_SCORE] == 1);
        BufferedImage img = new BufferedImage(1180, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); mc.print(g); g.dispose();
        javax.imageio.ImageIO.write(img, "png", shots.resolve("machine-lock.png").toFile());
        mc.pauseB.doClick();
        double tau0 = c.tau, s0 = lab.sigVal[Sfx.sigIdx("drive")];
        for (int i = 0; i < 60; i++) mc.frameTick();
        check("freeze stops machine time and the signals", c.tau == tau0 && lab.sigVal[Sfx.sigIdx("drive")] == s0 && mc.auto.paused);
        mc.pauseB.doClick();
        check("voice lever enabled at lock", mc.voiceB.isEnabled());
        mc.voiceB.doClick(); mc.frameTick();
        check("voiced: shelf entry, machine cleared", mc.shelf.size() == 1 && c.snapshot().isEmpty());
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
