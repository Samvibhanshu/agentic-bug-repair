package dev.samridhi.repair.coverage;

import static dev.samridhi.repair.testkit.Assert.assertEquals;
import static dev.samridhi.repair.testkit.Assert.assertFalse;
import static dev.samridhi.repair.testkit.Assert.assertTrue;

import dev.samridhi.repair.Fixtures;
import dev.samridhi.repair.agent.Verifier;
import dev.samridhi.repair.graph.CodeGraph;
import dev.samridhi.repair.graph.JavaIndexer;
import dev.samridhi.repair.localize.FaultLocalizer;
import dev.samridhi.repair.localize.Suspect;
import dev.samridhi.repair.sandbox.Limits;
import dev.samridhi.repair.sandbox.ProcessSandbox;
import dev.samridhi.repair.sandbox.Sandbox;
import dev.samridhi.repair.sandbox.SandboxResult;
import dev.samridhi.repair.testkit.Test;
import dev.samridhi.repair.trace.TestOutputParser;
import dev.samridhi.repair.trace.TestRun;
import dev.samridhi.repair.util.Fs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Measured per-test coverage: the instrumentation, what it measures, and the fallback when it cannot run. */
public class CoverageTests {
    private static final Limits LIMITS = new Limits(120_000, 2048, 128, 1 << 20);
    private static final List<String> BUILD = List.of("bash", "build.sh");

    @Test
    public void instrumentingKeepsEveryLineNumberAndTheTestsStillPass() throws Exception {
        Path repo = Fixtures.demo(Fixtures.tempDir("inst-"), Map.of());
        Path copy = Fixtures.tempDir("inst-copy-");
        Fs.copyTree(repo, copy);
        List<Instrumenter.Probe> probes = Instrumenter.instrument(copy);
        assertEquals(7, probes.size());                                   // Stats: constructor, max, mean, countAbove; Report: constructor, summary, outliers
        for (String f : List.of("src/main/java/demo/Stats.java", "src/main/java/demo/Report.java")) {
            List<String> before = Files.readAllLines(repo.resolve(f)), after = Files.readAllLines(copy.resolve(f));
            assertEquals(before.size(), after.size());                     // same number of lines
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.get(i), after.get(i).replaceAll(" repairprobe\\.RepairProbe\\.hit\\(\\d+\\);", ""));
            }
        }
        assertTrue(Files.readString(copy.resolve("src/test/java/demo/StatsTests.java")).equals(Fixtures.STATS_TESTS), "tests are never instrumented");
        SandboxResult r = new ProcessSandbox().run(copy, BUILD, LIMITS);
        TestRun run = TestOutputParser.parse(r.output(), r.timedOut());
        assertTrue(run.allGreen(), r.output());
        assertEquals(6, run.passed().size());
    }

    @Test
    public void coverageSaysWhichMethodsEachTestReallyRan() throws Exception {
        Path repo = Fixtures.demo(Fixtures.tempDir("cov-"), Map.of());
        CodeGraph g = JavaIndexer.index(repo);
        Coverage c = new Verifier(new ProcessSandbox(), BUILD, LIMITS).coverage(repo, g);
        assertEquals(6, c.methodsByTest().size());
        assertEquals(Set.of("demo.Stats#max(int[])"), c.of("StatsTests.maxOfSeveral"));
        assertEquals(Set.of("demo.Report#summary(int[])", "demo.Stats#max(int[])", "demo.Stats#mean(int[])"), c.of("ReportTests.summaryText"));
        assertEquals(Set.of("demo.Report#outliers(int[])", "demo.Stats#countAbove(int[],int)", "demo.Stats#mean(int[])"),
                c.of("ReportTests.outliersCountsValuesAboveTheMean"));
        assertTrue(Files.readString(repo.resolve("src/main/java/demo/Stats.java")).equals(Fixtures.STATS), "the original is never touched");
    }

    private static final String BRANCHY = """
            package demo;

            public final class Branchy {
                private Branchy() {}

                public static int pick(boolean left, int x) {
                    if (left) return Left.go(x);
                    return Right.go(x);
                }
            }
            """;
    private static final String LEFT = """
            package demo;

            final class Left {
                private Left() {}

                static int go(int x) {
                    return x - 1;
                }
            }
            """;
    private static final String RIGHT = """
            package demo;

            final class Right {
                private Right() {}

                static int go(int x) {
                    return x + 1;
                }
            }
            """;
    private static final String BRANCHY_TESTS = """
            package demo;

            import static dev.samridhi.repair.testkit.Assert.assertEquals;

            import dev.samridhi.repair.testkit.Test;

            public class BranchyTests {
                @Test
                public void leftAddsOne() {
                    assertEquals(4, Branchy.pick(true, 3));
                }

                @Test
                public void rightAddsOne() {
                    assertEquals(4, Branchy.pick(false, 3));
                }
            }
            """;

    @Test
    public void measuredCoverageTellsApartWhatTheCallGraphCannot() throws Exception {
        // Left.go is wrong. The failing test only takes the left branch, the passing one only the right. The call graph
        // says both tests can reach both methods, so the spectrum cannot choose; measured coverage can.
        Path repo = Fixtures.project(Fixtures.tempDir("branchy-"), Map.of("src/main/java/demo/Branchy.java", BRANCHY,
                "src/main/java/demo/Left.java", LEFT, "src/main/java/demo/Right.java", RIGHT,
                "src/test/java/demo/BranchyTests.java", BRANCHY_TESTS), "demo.BranchyTests");
        CodeGraph g = JavaIndexer.index(repo);
        Verifier v = new Verifier(new ProcessSandbox(), BUILD, LIMITS);
        TestRun run = v.baseline(repo);
        assertEquals(Set.of("BranchyTests.leftAddsOne"), run.failedNames());
        Coverage c = v.coverage(repo, g);
        List<Suspect> graphOnly = FaultLocalizer.localize(g, run, null, FaultLocalizer.Weights.SPECTRUM_ONLY);
        List<Suspect> measured = FaultLocalizer.localize(g, run, null, FaultLocalizer.Weights.SPECTRUM_ONLY, c);
        double left = score(graphOnly, "demo.Left#go(int)"), right = score(graphOnly, "demo.Right#go(int)");
        assertEquals(left, right);                                         // the graph cannot tell them apart
        assertEquals("demo.Left#go(int)", measured.get(0).id().equals("demo.Branchy#pick(boolean,int)") ? measured.get(1).id() : measured.get(0).id());
        assertEquals(0.0, score(measured, "demo.Right#go(int)"));          // never ran in the failing test
    }

    private static double score(List<Suspect> s, String id) {
        for (Suspect x : s) if (x.id().equals(id)) return x.signals().get("spectrum");
        return 0.0;
    }

    @Test
    public void constructorsGetTheProbeAfterSuperOrThis() throws Exception {
        String base = "package demo;\n\nclass Base {\n    final int v;\n\n    Base(int v) {\n        this.v = v;\n    }\n}\n";
        String child = "package demo;\n\nfinal class Child extends Base {\n    Child(int v) {\n        super(v + 1);\n    }\n\n    Child() {\n        this(0);\n    }\n}\n";
        String tests = BRANCHY_TESTS.replace("BranchyTests", "ChildTests").replace("assertEquals(4, Branchy.pick(true, 3));", "assertEquals(1, new Child().v);")
                .replace("assertEquals(4, Branchy.pick(false, 3));", "assertEquals(3, new Child(2).v);");
        Path repo = Fixtures.project(Fixtures.tempDir("ctor-"), Map.of("src/main/java/demo/Base.java", base, "src/main/java/demo/Child.java", child,
                "src/test/java/demo/ChildTests.java", tests), "demo.ChildTests");
        Path copy = Fixtures.tempDir("ctor-copy-");
        Fs.copyTree(repo, copy);
        Instrumenter.instrument(copy);
        String instrumented = Files.readString(copy.resolve("src/main/java/demo/Child.java"));
        assertTrue(instrumented.contains("super(v + 1); repairprobe.RepairProbe.hit("), instrumented);
        assertTrue(instrumented.contains("this(0); repairprobe.RepairProbe.hit("), instrumented);
        TestRun run = TestOutputParser.parse(new ProcessSandbox().run(copy, BUILD, LIMITS).output(), false);
        assertTrue(run.allGreen(), "the instrumented copy compiles and passes");
    }

    @Test
    public void withoutCoverageTheLocalizerFallsBackToTheGraph() throws Exception {
        Path repo = Fixtures.demo(Fixtures.tempDir("fallback-"), Map.of());
        CodeGraph g = JavaIndexer.index(repo);
        Sandbox broken = new Sandbox() {                                   // the instrumented copy "does not compile"
            @Override
            public SandboxResult run(Path r, List<String> c, Limits l) {
                return new SandboxResult(1, "src/main/java/demo/Stats.java:3: error: cannot find symbol\n", false, false, false, 5);
            }

            @Override
            public String describe() {
                return "stub";
            }
        };
        Coverage c = new Verifier(broken, BUILD, LIMITS).coverage(repo, g);
        assertTrue(c.isEmpty());
        assertTrue(c.note().contains("did not compile"), c.note());
        String out = "  FAIL  StatsTests.countAboveIsStrict  ->  java.lang.AssertionError: expected <1> but was <2>\n  PASS  StatsTests.maxOfSeveral\n";
        TestRun run = TestOutputParser.parse(out, false);
        assertEquals(FaultLocalizer.localize(g, run, null, FaultLocalizer.Weights.DEFAULT),
                FaultLocalizer.localize(g, run, null, FaultLocalizer.Weights.DEFAULT, c));
    }

    @Test
    public void theLogicSignalCountsDecisionsNotBoilerplate() {
        assertEquals(0.0, FaultLocalizer.logic("int size() { return size; }"));
        assertEquals(0.0, FaultLocalizer.logic("QueueConfig withRetention(long ms) { return new QueueConfig(a, b, ms, c); }"));
        assertEquals(0.0, FaultLocalizer.logic("List<Map<String, Long>> all() { return new ArrayList<List<String>>(); }"));   // generics are not comparisons
        assertEquals(0.0, FaultLocalizer.logic("String s() { return \"a < b && c\"; } // if x > y"));                           // nor strings and comments
        double branchy = FaultLocalizer.logic("boolean ok(int a, int b) { if (a < b && b != 0) return a % b == 0; for (;;) { break; } }");
        assertTrue(branchy > 0.7 && branchy < 1, "score " + branchy);
        assertTrue(FaultLocalizer.logic("int f(int x) { return x + 1; }") < branchy);
        assertFalse(FaultLocalizer.logic("int f(int x) { return x + 1; }") == 0.0);
    }
}
