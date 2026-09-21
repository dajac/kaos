import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks the one layout rule that the formatter cannot enforce: a line that closes more parentheses
 * than it opens starts with {@code )} or {@code }}. In other words, the closing parenthesis of a call
 * whose arguments span several lines is on its own line, not behind the last argument.
 *
 * <p>Parentheses in comments, strings, characters and text blocks do not count. The build runs this
 * file with the source launcher, first on its fixture and then on the project:
 *
 * <pre>
 * java config/CheckParentheses.java --self-test config/check-parentheses-fixture.txt
 * java config/CheckParentheses.java &lt;project directory&gt;
 * </pre>
 */
public final class CheckParentheses {

    /** Marks, in the fixture, the lines that the check must report. No other line may be reported. */
    private static final String EXPECTED_MARKER = "// VIOLATION";

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", "node_modules", ".git");

    private enum State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHARACTER, TEXT_BLOCK }

    private CheckParentheses() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--self-test")) {
            selfTest(Path.of(args[1]));
        } else if (args.length == 1) {
            checkProject(Path.of(args[0]));
        } else {
            fail("Usage: CheckParentheses.java <project directory> | --self-test <fixture>");
        }
    }

    /** Checks every {@code src/main/java} and {@code src/test/java} directory under the project. */
    private static void checkProject(Path project) throws IOException {
        var roots = sourceRoots(project);
        if (roots.isEmpty()) {
            fail("No src/main/java or src/test/java directory under " + project.toAbsolutePath());
        }
        var violations = new ArrayList<String>();
        for (var root : roots) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    check(Files.readString(path)).forEach(line -> violations.add(path + ":" + line));
                }
            }
        }
        if (!violations.isEmpty()) {
            violations.forEach(System.err::println);
            fail(
                violations.size() + " line(s) close a parenthesis opened on an earlier line without starting"
                    + " with ')' or '}'. Run './mvnw spotless:apply' first; if a line is still reported,"
                    + " move its closing parenthesis to its own line."
            );
        }
    }

    private static List<Path> sourceRoots(Path project) throws IOException {
        var roots = new ArrayList<Path>();
        Files.walkFileTree(project, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (directory.endsWith("src/main/java") || directory.endsWith("src/test/java")) {
                    roots.add(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return roots;
    }

    /** Checks that the lines reported in the fixture are exactly the marked ones. */
    private static void selfTest(Path fixture) throws IOException {
        var source = Files.readString(fixture);
        var expected = new ArrayList<Integer>();
        var lines = source.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].contains(EXPECTED_MARKER)) {
                expected.add(i + 1);
            }
        }
        var actual = check(source);
        if (expected.isEmpty() || !expected.equals(actual)) {
            fail("Self-test failed on " + fixture + ": expected the lines " + expected + ", got " + actual);
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    /** The numbers of the lines that break the rule, in ascending order. */
    static List<Integer> check(String source) {
        var violations = new ArrayList<Integer>();
        var state = State.CODE;
        var line = 1;
        var balance = 0;
        var first = '\0';
        for (var i = 0; i <= source.length(); i++) {
            var c = i < source.length() ? source.charAt(i) : '\n';
            if (c == '\n') {
                if (balance < 0 && first != ')' && first != '}') {
                    violations.add(line);
                }
                if (state == State.LINE_COMMENT) {
                    state = State.CODE;
                }
                line++;
                balance = 0;
                first = '\0';
                continue;
            }
            switch (state) {
                case CODE -> {
                    if (source.startsWith("//", i)) {
                        state = State.LINE_COMMENT;
                    } else if (source.startsWith("/*", i)) {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (source.startsWith("\"\"\"", i)) {
                        state = State.TEXT_BLOCK;
                        i += 2;
                    } else if (c == '"') {
                        state = State.STRING;
                    } else if (c == '\'') {
                        state = State.CHARACTER;
                    } else if (c == '(') {
                        balance++;
                    } else if (c == ')') {
                        balance--;
                    }
                    var comment = state == State.LINE_COMMENT || state == State.BLOCK_COMMENT;
                    if (first == '\0' && !comment && !Character.isWhitespace(c)) {
                        first = c;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (source.startsWith("*/", i)) {
                        state = State.CODE;
                        i++;
                    }
                }
                case STRING, CHARACTER -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == (state == State.STRING ? '"' : '\'')) {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '\\' && !source.startsWith("\n", i + 1)) {
                        i++;
                    } else if (source.startsWith("\"\"\"", i)) {
                        state = State.CODE;
                        i += 2;
                    }
                }
                case LINE_COMMENT -> {}
            }
        }
        return violations;
    }
}
