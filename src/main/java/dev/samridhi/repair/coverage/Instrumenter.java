package dev.samridhi.repair.coverage;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Adds a probe to the start of every method body in a repository's non-test sources, so that one run of the test
 * suite reports which methods each test actually executed (method-level coverage, the input of spectrum-based fault
 * localization). Only a throwaway copy is ever changed.
 *
 * <p>The probe call goes on the same line as the method's opening brace, so every line number in the copy, and so
 * every stack trace, is the same as in the original. In a constructor it goes after {@code super(...)} or
 * {@code this(...)}, which must come first. Methods of anonymous and local classes get probes too; the graph counts
 * their code as part of the enclosing method, and so does the mapping back ({@link #probes} records lines, which
 * {@code CodeGraph.methodAt} resolves to the innermost named method).
 */
public final class Instrumenter {
    /** Where probe {@code id} sits: a file (relative, as in the code graph) and the line of the method's opening brace. */
    public record Probe(int id, String file, int line) {}

    static final String PACKAGE = "repairprobe";
    static final String CLASS = "RepairProbe";
    private static final Set<String> SKIP_DIRS = Set.of("out", "target", "build", ".git", "node_modules", ".gradle", ".idea");

    private Instrumenter() {}

    /** Instruments {@code copy} in place and returns the probes, or an empty list if there was nothing to instrument. */
    public static List<Probe> instrument(Path copy) throws IOException {
        Path root = copy.toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                for (String part : rel.split("/")) if (SKIP_DIRS.contains(part)) return false;
                return !isTestFile(rel);
            }).sorted().collect(Collectors.toList());
        }
        if (files.isEmpty()) return List.of();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("a JDK is required");
        List<Probe> probes = new ArrayList<>();
        Path sourceRoot = null;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> {}, List.of("-proc:none"), null, fm.getJavaFileObjectsFromPaths(files));
            SourcePositions pos = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree cu : task.parse()) {
                Path file = Path.of(cu.getSourceFile().toUri());
                String rel = root.relativize(file).toString().replace('\\', '/');
                String text = Files.readString(file, StandardCharsets.UTF_8);
                TreeMap<Integer, String> inserts = new TreeMap<>();          // offset -> text, applied back to front
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree m, Void v) {
                        BlockTree body = m.getBody();
                        if (body != null) {
                            long open = pos.getStartPosition(cu, body);       // the '{'
                            long at = open + 1;
                            List<? extends StatementTree> stmts = body.getStatements();
                            if (!stmts.isEmpty() && isConstructorCall(stmts.get(0))) at = pos.getEndPosition(cu, stmts.get(0));
                            if (open >= 0 && at > 0 && text.charAt((int) open) == '{') {
                                int id = probes.size();
                                int line = (int) cu.getLineMap().getLineNumber(open);
                                probes.add(new Probe(id, rel, line));
                                inserts.put((int) at, " " + PACKAGE + "." + CLASS + ".hit(" + id + ");");
                            }
                        }
                        return super.visitMethod(m, v);
                    }
                }.scan(cu, null);
                if (inserts.isEmpty()) continue;
                StringBuilder out = new StringBuilder(text);
                for (var e : inserts.descendingMap().entrySet()) out.insert(e.getKey(), e.getValue());
                Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
                if (sourceRoot == null) sourceRoot = sourceRootOf(file, cu);
            }
        }
        if (probes.isEmpty()) return probes;
        Path dir = sourceRoot.resolve(PACKAGE);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(CLASS + ".java"), probeSource(probes.size()), StandardCharsets.UTF_8);
        return probes;
    }

    /** The same rule the code graph uses, so exactly the candidate (non-test) methods get probes. */
    static boolean isTestFile(String rel) {
        return rel.contains("/test/") || rel.startsWith("test/") || rel.matches(".*Tests?\\.java");
    }

    private static boolean isConstructorCall(StatementTree s) {
        if (!(s instanceof ExpressionStatementTree es) || !(es.getExpression() instanceof MethodInvocationTree mi)) return false;
        String name = mi.getMethodSelect() instanceof IdentifierTree id ? id.getName().toString() : mi.getMethodSelect().toString();
        return name.equals("super") || name.equals("this") || name.endsWith(".super");
    }

    private static Path sourceRootOf(Path file, CompilationUnitTree cu) {
        Path dir = file.getParent();
        if (cu.getPackageName() == null) return dir;
        for (String ignored : cu.getPackageName().toString().split("\\.")) dir = dir.getParent();
        return dir;
    }

    /**
     * The probe. It wraps System.out so that, just before the test runner prints a test's PASS or FAIL line, it prints
     * {@code REPAIR-COVERAGE <ids>} with the methods hit since the previous test, and resets. A START line (if the
     * runner prints one) also resets, so set-up between tests is not charged to the next one.
     */
    static String probeSource(int n) {
        return """
                package %s;

                import java.util.concurrent.atomic.AtomicIntegerArray;

                /** Inserted by agentic-bug-repair into a throwaway copy to measure per-test coverage. Not part of the project. */
                public final class %s {
                    private static final AtomicIntegerArray HIT = new AtomicIntegerArray(%d);

                    static {
                        final java.io.PrintStream real = System.out;
                        System.setOut(new java.io.PrintStream(real, true) {
                            @Override
                            public void println(String x) {
                                if (x != null) {
                                    String t = x.trim();
                                    if (t.startsWith("START ")) drain();
                                    else if (t.startsWith("PASS ") || t.startsWith("FAIL ")) super.println("REPAIR-COVERAGE" + drain());
                                }
                                super.println(x);
                            }
                        });
                    }

                    private %s() {}

                    public static void hit(int id) {
                        if (HIT.get(id) == 0) HIT.set(id, 1);
                    }

                    private static synchronized String drain() {
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < HIT.length(); i++) {
                            if (HIT.get(i) != 0) {
                                HIT.set(i, 0);
                                b.append(' ').append(i);
                            }
                        }
                        return b.toString();
                    }
                }
                """.formatted(PACKAGE, CLASS, Math.max(1, n), CLASS);
    }
}
