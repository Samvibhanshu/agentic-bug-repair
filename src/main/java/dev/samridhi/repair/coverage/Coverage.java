package dev.samridhi.repair.coverage;

import dev.samridhi.repair.graph.CodeGraph;
import dev.samridhi.repair.graph.CodeNode;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which methods each test executed, measured by running an instrumented copy (see {@link Instrumenter}).
 * Tests whose coverage could not be read are simply absent; the localizer then falls back to the call graph for them.
 */
public record Coverage(Map<String, Set<String>> methodsByTest, int probes, String note) {
    private static final Pattern RESULT = Pattern.compile("^\\s*(?:PASS|FAIL)\\s+(\\S+).*$");

    public Coverage {
        methodsByTest = Map.copyOf(methodsByTest);
    }

    public static Coverage none(String why) {
        return new Coverage(Map.of(), 0, why);
    }

    /** Methods covered by {@code test}, or null if unknown. */
    public Set<String> of(String test) {
        return methodsByTest.get(test);
    }

    public boolean isEmpty() {
        return methodsByTest.isEmpty();
    }

    /** Reads the REPAIR-COVERAGE lines of an instrumented run and maps probe ids back to code-graph method ids. */
    public static Coverage parse(String output, List<Instrumenter.Probe> probes, CodeGraph graph) {
        Map<Integer, String> method = new HashMap<>();
        for (Instrumenter.Probe p : probes) {
            CodeNode n = graph.methodAt(p.file(), p.line());
            if (n != null) method.put(p.id(), n.id());
        }
        Map<String, Set<String>> byTest = new HashMap<>();
        Set<String> pending = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("REPAIR-COVERAGE")) {
                pending = new HashSet<>();
                for (String tok : line.substring("REPAIR-COVERAGE".length()).trim().split(" ")) {
                    if (tok.isEmpty()) continue;
                    String m = method.get(Integer.parseInt(tok));
                    if (m != null) pending.add(m);
                }
                continue;
            }
            Matcher r = RESULT.matcher(line);
            if (r.matches() && pending != null) {
                byTest.put(r.group(1), Collections.unmodifiableSet(pending));
                pending = null;
            }
        }
        return new Coverage(byTest, probes.size(), byTest.isEmpty() ? "the instrumented run printed no coverage" : null);
    }
}
