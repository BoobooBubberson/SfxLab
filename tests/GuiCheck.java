import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import sfxlab.runtime.*;

/** Headless checks of the authoring GUI around the shared runtime: the regulator panel's bind tables (mutes, filter,
 *  undo) and the machine window conducted to a lock and voicing the crystal, by hand and by its auto-player. Loads the pyretic family without assuming
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

        // edits go to the family's working copy; only S writes the family file
        Path famFile = SfxLab.familyFile("pyretic"), work = SfxLab.workingFile("pyretic");
        Clip ed = lab.bench.layers.get(0);
        double before = ed.p[Sfx.P_LEVEL], edited = before > 0.5 ? before - 0.25 : before + 0.25;
        ed.p[Sfx.P_LEVEL] = edited; lab.markEdit(); lab.saveBench(true);
        check("an edit autosaves to the working copy and leaves the family file alone",
              Files.exists(work) && lab.unsaved && Files.readString(famFile).equals(disk));
        lab.loadFamily("pyretic");
        check("reopening the family brings its unsaved edit back", lab.unsaved && lab.bench.layers.get(0).p[Sfx.P_LEVEL] == edited);
        lab.revertFamily();
        check("shift+O reverts to the saved file and drops the working copy", !lab.unsaved && !Files.exists(work) && lab.bench.layers.get(0).p[Sfx.P_LEVEL] == before);
        lab.bench.layers.get(0).p[Sfx.P_LEVEL] = edited; lab.markEdit(); lab.saveBench(true);
        lab.saveFamily();
        check("S writes the family file and clears the working copy", !lab.unsaved && !Files.exists(work) && !Files.readString(famFile).equals(disk)
              && SfxFormat.parseFamily(Files.readAllLines(famFile)).palette().layers.get(0).p[Sfx.P_LEVEL] == edited);
        Files.writeString(famFile, disk); lab.loadFamily("pyretic", false);   // the workspace's copy as it was

        // shift+M: the mono audition (the game's one positional source), remembered in lab.cfg (the test workspace's)
        boolean mono0 = lab.monoOut;
        java.awt.event.KeyEvent sm = new java.awt.event.KeyEvent(lab, java.awt.event.KeyEvent.KEY_PRESSED, 0, java.awt.event.KeyEvent.SHIFT_DOWN_MASK, java.awt.event.KeyEvent.VK_M, 'M');
        lab.handleKey(sm);
        boolean saved = Files.readString(SfxLab.CFG_FILE).contains("mono_out=" + (lab.monoOut ? 1 : 0));
        check("shift+M toggles the mono audition and remembers it", lab.monoOut != mono0 && saved);
        lab.handleKey(sm);

        // the machine, conducted as the game conducts it: seat a crystal, build a spell by the casts, lock, freeze, voice
        SfxLab.Machine mc = new SfxLab.Machine(lab);
        mc.setSize(1180, 760); mc.addNotify(); layoutAll(mc); layoutAll(mc);
        RegulatorCore c = mc.core;
        ConductedMachine cm = mc.cm;
        check("the machine is the game's: free, on the wells, one motion in hand at a time", c.free && c.wells && !c.classic && !c.coupling && c.arms() == 3);
        RegulatorCore.Recipe target = null;
        for (RegulatorCore.Recipe r : c.recipes) if (r.comps.length == 2) { target = r; break; }
        check("the family has a two-motion recipe to play", target != null);
        if (target == null) { System.out.println(fails + " FAILED"); System.exit(1); }
        mc.pin(target);
        mc.toggle(new SfxLab.Machine.Pick(SfxLab.Machine.On.CRYSTAL, -1, -1, 0, 0, 1, false));
        check("no crystal seated: the casts do nothing", cm.selectedAxis() == 0);
        mc.power.doClick();
        check("seating a crystal starts the bench playing", c.powered && lab.benchPlaying);
        final double DT = 1 / 60.0;
        for (RegulatorCore.Comp k : target.comps) {   // each component by its casts: the socket's toggle, reach, the aim, the phase, planted
            for (int i = 0; i < 3 && cm.selectedAxis() != k.axis(); i++) mc.toggle(new SfxLab.Machine.Pick(SfxLab.Machine.On.CRYSTAL, -1, -1, 0, 0, 1, false));
            mc.beginThread(new SfxLab.Machine.Pick(SfxLab.Machine.On.ARM, k.axis(), cm.selectedSlot(), 0, 0, 1, true), true);
            for (int i = 0; i < 600 && cm.selectedReach() < k.amp() - 0.03; i++) mc.step(DT);
            mc.endThread();
            mc.beginThread(new SfxLab.Machine.Pick(SfxLab.Machine.On.COMPOSITE, -1, -1, 0, 0, 1, false), cm.aim() < k.n());
            for (int i = 0; i < 2000 && Math.abs(cm.aim() - k.n()) > 0.06; i++) mc.step(DT);
            mc.endThread();
            RegulatorCore.Motion mo = cm.motion(k.axis(), cm.selectedSlot());
            for (int i = 0; i < 600 && !(mo.r == k.n() && !mo.drv); i++) mc.step(DT);
            cm.phase(k.phase() - cm.phaseAngle(k.axis(), cm.selectedSlot())); cm.phaseLetGo();
            mc.step(DT);
            mc.toggle(new SfxLab.Machine.Pick(SfxLab.Machine.On.COMPOSITE, -1, -1, 0, 0, 1, false));
        }
        for (int i = 0; i < 30; i++) mc.step(DT);
        check("the casts built " + target.name + ", and the lock is handed to the bench (score " + lab.sigVal[Sfx.SIG_SCORE] + ")", c.matched == target && lab.sigVal[Sfx.SIG_SCORE] == 1);
        BufferedImage img = new BufferedImage(1180, 760, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); mc.print(g); g.dispose();
        javax.imageio.ImageIO.write(img, "png", shots.resolve("machine-lock.png").toFile());
        check("the stage offers the composite, the seated crystal and the planted components to the hand",
              mc.picks.stream().anyMatch(p -> p.on() == SfxLab.Machine.On.COMPOSITE) && mc.picks.stream().anyMatch(p -> p.on() == SfxLab.Machine.On.CRYSTAL)
              && mc.picks.stream().filter(p -> p.on() == SfxLab.Machine.On.COMPONENT).count() == target.comps.length);
        for (boolean rising : new boolean[]{true, false}) {   // the thread is drawn for both verbs (a crescendo's once threw in the paint)
            mc.beginThread(new SfxLab.Machine.Pick(SfxLab.Machine.On.ARM, cm.selectedAxis(), cm.selectedSlot(), 0, 0, 1, true), rising);
            mc.step(DT);
            boolean painted = true;
            try { Graphics2D g2 = img.createGraphics(); mc.print(g2); g2.dispose(); } catch (RuntimeException e) { painted = false; }
            mc.endThread();
            check("the stage paints with a " + (rising ? "crescendo" : "diminuendo") + " thread in hand", painted);
        }
        mc.pauseB.doClick();
        double tau0 = c.tau, s0 = lab.sigVal[Sfx.sigIdx("drive")];
        for (int i = 0; i < 60; i++) mc.frameTick();
        check("freeze stops machine time and the signals", c.tau == tau0 && lab.sigVal[Sfx.sigIdx("drive")] == s0 && mc.auto.paused);
        mc.pauseB.doClick();
        mc.beginThread(new SfxLab.Machine.Pick(SfxLab.Machine.On.CRYSTAL, -1, -1, 0, 0, 1, false), true);
        for (int i = 0; i < 40; i++) mc.step(DT);
        mc.endThread();
        check("crescendo into the seated crystal at a lock voices it: the machine is cleared", mc.voicedCount == 1 && c.snapshot().isEmpty() && c.lastVoiced == target);

        // the auto-player conducts every spell of the family to a lock with the same casts, with and without its meanders
        for (RegulatorCore.Recipe r : c.recipes) {
            if (r.secret) continue;
            for (int pass = 0; pass < 2; pass++) {
                mc.pin(r);
                mc.auto.rng = new Random(11 + pass);
                mc.auto.mistakes = pass == 1;
                mc.auto.start();
                boolean locked = false;
                double t = 0;
                for (; t < 240 && !locked; t += 1 / 30.0) { mc.step(1 / 30.0); locked = c.matched == r; }
                mc.auto.stop();
                check(String.format(Locale.ROOT, "auto-play conducts %s to a lock%s (%.0f s)", r.name, pass == 1 ? ", meandering" : "", t), locked);
            }
        }
        // closing the window ends the session: nothing is left running or seated for the next one
        mc.pin(target);
        mc.auto.start(); mc.autoB.setSelected(true);
        for (int i = 0; i < 300; i++) mc.step(1 / 30.0);
        mc.pauseB.doClick();
        mc.closed();
        check("closing the window stops auto-play, takes the crystal out, clears the machine and stops the bench",
              !mc.auto.on && !mc.auto.paused && !mc.autoB.isSelected() && !mc.power.isSelected() && !c.powered && c.snapshot().isEmpty() && !lab.benchPlaying);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
