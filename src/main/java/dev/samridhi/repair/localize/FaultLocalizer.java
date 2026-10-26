package dev.samridhi.repair.localize;

import dev.samridhi.repair.coverage.Coverage;
import dev.samridhi.repair.graph.CodeGraph;
import dev.samridhi.repair.graph.CodeNode;
import dev.samridhi.repair.graph.NodeKind;
import dev.samridhi.repair.retrieval.HybridRetriever;
import dev.samridhi.repair.trace.FailureReport;
import dev.samridhi.repair.trace.Frame;
import dev.samridhi.repair.trace.TestRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the methods of a repository by how likely each is to contain the bug behind a set of failing tests. Four
 * independent signals, each scaled to 0..1, are combined with a weighted sum:
 *
 * <ul>
 *   <li><b>spectrum</b> - Ochiai suspiciousness: methods executed by many failing tests and few passing tests score
 *       high (spectrum-based fault localisation). Which methods a test executes comes from a real, instrumented run when
 *       {@link Coverage} is available ({@code dev.samridhi.repair.coverage}); otherwise the call graph stands in for it
 *       (a method is "covered" if the graph reaches it from the test), which over-approximates: every branch of every
 *       method the test could call counts, so integration-style tests seem to cover almost everything.</li>
 *   <li><b>frame</b> - the stack trace. The innermost project (non-test) frame is where the program went wrong; frames
 *       further out score less. Assertion failures have none, because the exception is thrown inside the test.</li>
 *   <li><b>proximity</b> - 1 / (call-chain length from the failing test). A bug is more often in code the test calls
 *       directly than seven levels down.</li>
 *   <li><b>text</b> - hybrid BM25 + vector search of the failing test's source and error message against method bodies.</li>
 *   <li><b>logic</b> - how much decision and arithmetic code the method has (branches, loops, comparisons, boolean and
 *       arithmetic operators), as k / (k + 2). A one-line setter or a configuration builder called only by the failing
 *       tests looks perfectly suspicious to the spectrum, but has almost nothing that can be wrong. A prior, not
 *       evidence: it only reorders methods the other signals already point at.</li>
 * </ul>
 */
public final class FaultLocalizer {
    public static final int MAX_DEPTH = 8;

    /**
     * Signal weights. They need not sum to 1; scores are only compared with each other. The default was chosen by grid
     * search over {0, 0.25, 0.5, 1} per weight on the benchmark's <em>dev</em> split only ({@code bench --mode tune});
     * the held-out test split was not used to choose it. Two settings tied on dev (stack frame 0 or 0.25); 0.25 was kept
     * so that crashes, where the stack trace is strong evidence, still use it.
     */
    public record Weights(double spectrum, double frame, double proximity, double text, double logic) {
        /** The weights used before measured coverage and the logic signal (kept for the benchmark's comparison). */
        public static final Weights BEFORE = new Weights(0.5, 0.5, 0.25, 0.05, 0);
        public static final Weights DEFAULT = new Weights(1.0, 0.25, 0, 0.25, 0.25);
        public static final Weights TRACE_ONLY = new Weights(0, 1, 0, 0, 0);
        public static final Weights TEXT_ONLY = new Weights(0, 0, 0, 1, 0);
        public static final Weights SPECTRUM_ONLY = new Weights(1, 0, 0, 0, 0);
        public static final Weights NO_GRAPH = new Weights(0, 0.5, 0, 0.5, 0);
    }

    private FaultLocalizer() {}

    public static List<Suspect> localize(CodeGraph g, TestRun run, HybridRetriever retriever, Weights w) {
        return localize(g, run, retriever, w, null);
    }

    /** As above, with measured per-test coverage (null or empty: the call graph is used for every test). */
    public static List<Suspect> localize(CodeGraph g, TestRun run, HybridRetriever retriever, Weights w, Coverage coverage) {
        List<FailureReport> failures = run.failed();
        Map<String, Integer> failingReach = new HashMap<>();      // method -> number of failing tests executing it
        Map<String, Integer> passingReach = new HashMap<>();
        Map<String, Integer> nearest = new HashMap<>();            // method -> fewest calls from any failing test
        for (FailureReport f : failures) {
            CodeNode t = testNode(g, f.testName());
            Set<String> measured = coverage == null ? null : coverage.of(f.testName());
            Map<String, Integer> reach = t == null ? Map.of() : g.reachableByCalls(t.id(), MAX_DEPTH);
            if (measured != null) {
                for (String m : measured) {
                    if (!isCandidate(g, m)) continue;
                    failingReach.merge(m, 1, Integer::sum);
                    Integer d = reach.get(m);                       // executed but not on a static call chain: no proximity
                    if (d != null) nearest.merge(m, d, Math::min);
                }
                continue;
            }
            for (Map.Entry<String, Integer> e : reach.entrySet()) {
                if (!isCandidate(g, e.getKey())) continue;
                failingReach.merge(e.getKey(), 1, Integer::sum);
                nearest.merge(e.getKey(), e.getValue(), Math::min);
            }
        }
        for (String name : run.passed()) {
            Set<String> measured = coverage == null ? null : coverage.of(name);
            if (measured != null) {
                for (String m : measured) if (isCandidate(g, m)) passingReach.merge(m, 1, Integer::sum);
                continue;
            }
            CodeNode t = testNode(g, name);
            if (t == null) continue;
            for (String m : g.reachableByCalls(t.id(), MAX_DEPTH).keySet()) {
                if (isCandidate(g, m)) passingReach.merge(m, 1, Integer::sum);
            }
        }

        Map<String, Double> frameScore = new HashMap<>();
        for (FailureReport f : failures) {
            int depth = 0;
            for (Frame fr : f.frames()) {
                CodeNode n = resolve(g, fr);
                if (n == null || !isCandidate(g, n.id())) continue;
                frameScore.merge(n.id(), Math.pow(0.8, depth), Math::max);
                depth++;
            }
        }

        Map<String, Double> text = Map.of();
        if (w.text() > 0 && retriever != null && !failures.isEmpty()) {
            StringBuilder q = new StringBuilder();
            for (FailureReport f : failures) {
                q.append(f.summary()).append('\n');
                CodeNode t = testNode(g, f.testName());
                if (t != null) q.append(t.source()).append('\n');
            }
            text = retriever.normalizedScores(q.toString());
        }

        Set<String> candidates = new HashSet<>();
        candidates.addAll(failingReach.keySet());
        candidates.addAll(frameScore.keySet());
        for (String id : text.keySet()) if (isCandidate(g, id)) candidates.add(id);

        int totalFailing = failures.size();
        List<Suspect> out = new ArrayList<>();
        for (String id : candidates) {
            int ef = failingReach.getOrDefault(id, 0), ep = passingReach.getOrDefault(id, 0);
            double spectrum = totalFailing == 0 || ef == 0 ? 0 : ef / Math.sqrt((double) totalFailing * (ef + ep));
            double frame = frameScore.getOrDefault(id, 0.0);
            double prox = nearest.containsKey(id) ? 1.0 / Math.max(1, nearest.get(id)) : 0;
            double txt = text.getOrDefault(id, 0.0);
            double evidence = w.spectrum() * spectrum + w.frame() * frame + w.proximity() * prox + w.text() * txt;
            if (evidence <= 0) continue;                           // the prior alone never makes a suspect
            double logic = logic(g.node(id).source());
            double score = evidence + w.logic() * logic;
            Map<String, Double> signals = new LinkedHashMap<>();
            signals.put("spectrum", spectrum);
            signals.put("frame", frame);
            signals.put("proximity", prox);
            signals.put("text", txt);
            signals.put("logic", logic);
            out.add(new Suspect(id, score, 0, signals));
        }
        out.sort((a, b) -> {
            int c = Double.compare(b.score(), a.score());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        List<Suspect> ranked = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) ranked.add(new Suspect(out.get(i).id(), out.get(i).score(), i + 1, out.get(i).signals()));
        return ranked;
    }

    private static final java.util.regex.Pattern DECISIONS = java.util.regex.Pattern.compile(
            "\\b(if|for|while|case|catch)\\b|&&|\\|\\||\\?|==|!=|<=|>=| < | > | \\+ | - | \\* | / | % |\\+\\+|--|\\+=|-=");

    /**
     * The logic signal: decision points, comparisons and arithmetic in a method body, as k / (k + 2) in 0..1. Comments
     * and string literals are removed first. Comparisons count only with spaces around them ({@code a < b}), so the
     * angle brackets of generic types ({@code List<String>}) do not.
     */
    public static double logic(String methodSource) {
        String code = stripCommentsAndStrings(methodSource == null ? "" : methodSource);
        int body = code.indexOf('{');
        if (body >= 0) code = code.substring(body + 1);
        java.util.regex.Matcher m = DECISIONS.matcher(code);
        int k = 0;
        while (m.find()) k++;
        return k / (k + 2.0);
    }

    static String stripCommentsAndStrings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i = s.indexOf("*/", i + 2);
                if (i < 0) break;
                i++;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char q = c;
                for (i++; i < s.length() && s.charAt(i) != q; i++) if (s.charAt(i) == '\\') i++;
                out.append("\"\"");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isCandidate(CodeGraph g, String id) {
        CodeNode n = g.node(id);
        return n != null && n.kind() == NodeKind.METHOD && !n.isTest();
    }

    /** The graph node for a test printed as {@code Class.method}. */
    public static CodeNode testNode(CodeGraph g, String testName) {
        int dot = testName.lastIndexOf('.');
        if (dot < 0) return null;
        List<CodeNode> found = g.findMethods(testName.substring(0, dot), testName.substring(dot + 1));
        for (CodeNode n : found) if (n.isTest()) return n;
        return found.isEmpty() ? null : found.get(0);
    }

    /** Maps a stack frame to the method it executes, by source file and line. */
    public static CodeNode resolve(CodeGraph g, Frame f) {
        String pkgPath = "";
        String cls = f.className();
        int lastDot = cls.lastIndexOf('.');
        if (lastDot >= 0) pkgPath = cls.substring(0, lastDot).replace('.', '/') + "/";
        String suffix = pkgPath + f.fileName();
        for (CodeNode n : g.nodes()) {
            if (n.kind() == NodeKind.CLASS && (n.file().equals(suffix) || n.file().endsWith("/" + suffix))) {
                return g.methodAt(n.file(), f.line());
            }
        }
        return null;
    }
}
