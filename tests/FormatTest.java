import java.nio.file.*;
import java.util.*;
import sfxlab.runtime.*;

/** The family format, runtime only: every fixture family validates clean and re-writes to the same text; one of each
 *  kind of problem, injected into a real family, is named by validateFamily. Run by tests/run.sh (in its workspace). */
public class FormatTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        Path reg = Paths.get(a.length > 0 ? a[0] : "tests/fixtures/regulator");
        List<Path> fams = new ArrayList<>();
        try (var st = Files.list(reg)) { st.filter(p -> p.toString().endsWith(".sfx")).sorted().forEach(fams::add); }
        for (Path f : fams) {
            List<String> lines = Files.readAllLines(f);
            List<String> probs = SfxFormat.validateFamily(lines);
            check(f.getFileName() + " validates clean" + (probs.isEmpty() ? "" : ": " + probs), probs.isEmpty());
            Family fm = SfxFormat.parseFamily(lines);
            String once = SfxFormat.familyText(fm.palette(), fm.spells(), fm.palette().root);
            Family again = SfxFormat.parseFamily(Arrays.asList(once.split("\n")));
            check(f.getFileName() + " re-writes to the same text", once.equals(SfxFormat.familyText(again.palette(), again.spells(), again.palette().root)));
            check(f.getFileName() + " carries format " + SfxFormat.FAMILY_FORMAT, lines.contains("bench " + SfxFormat.FAMILY_FORMAT));
        }

        // one of each problem, in a copy of the first family
        List<String> base = Files.readAllLines(fams.get(0));
        Family fm = SfxFormat.parseFamily(base);
        String lay = fm.palette().layers.get(0).id;
        Map<String, String> cases = new LinkedHashMap<>();   // injected line(s) -> a word the report must contain
        cases.put("wobble 1 2 3", "unknown line type");
        cases.put("bind drive nosuchlayer level 0 1", "no layer here");
        cases.put("bind drive " + lay + " nosuchparam 0 1", "no such param");
        cases.put("bind nosuchsignal " + lay + " level 0 1", "unknown signal");
        cases.put("bind score.nosuchspell " + lay + " level 0 1", "unknown signal");
        cases.put("bind drive " + lay + " level 0.2", "both lo and hi");
        cases.put("bind drive " + lay + " level 0 1 scale=nosuchchord", "unknown mapping");
        cases.put("range nosuchlayer level 0 1", "no layer here");
        cases.put("layer extra thing 0 0 7 level=0.5 wobble=3", "has no param");
        cases.put("layer extra2 thing 0 0 7 level=0.5 on=sometimes", "is not one of");
        cases.put("layer " + lay + " dup 0 0 7 level=0.5", "appears twice");
        cases.put("bench " + (SfxFormat.FAMILY_FORMAT + 1), "newer than this runtime");
        cases.put("recipe tier=1 X3p1", "belongs in a spell section");
        cases.put("spell broken\nname Broken\nrecipe tier=1 X3p1 Y2p0 Z1p0", "slots");
        cases.put("spell broken2\nname Broken\nrecipe tier=4 X3p1", "tiers run");
        cases.put("spell broken3\nname Broken\nrecipe tier=1 X3q1", "is not a motion");
        cases.put("spell norecipe\nname No recipe", "has no recipe");
        cases.put("spell " + fm.spells().get(0).id + "\nrecipe tier=1 X1p0", "appears twice");
        for (var e : cases.entrySet()) {
            List<String> lines = new ArrayList<>(base);
            String inj = e.getKey();
            if (inj.startsWith("spell ")) lines.addAll(Arrays.asList(inj.split("\n")));
            else {   // palette lines go before the first spell section
                int at = 0; while (at < lines.size() && !lines.get(at).startsWith("spell ")) at++;
                lines.addAll(at, Arrays.asList(inj.split("\n")));
            }
            List<String> probs = SfxFormat.validateFamily(lines);
            boolean named = probs.stream().anyMatch(p -> p.contains(e.getValue()));
            check("names `" + inj.replace("\n", " / ") + "`" + (named ? "" : " (got " + probs + ")"), named);
        }
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
