import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the one layout rule that the formatter cannot enforce: a line that closes more parentheses
 * than it opens starts with {@code )} or {@code }}. In other words, the closing parenthesis of a call
 * whose arguments span several lines is on its own line, not behind the last argument.
 *
 * <p>Parentheses in comments, strings, characters and text blocks do not count. The build runs this
 * file with the source launcher: {@code java config/CheckParentheses.java <directory>...}.
 */
public final class CheckParentheses {

    private enum State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHARACTER, TEXT_BLOCK }

    private CheckParentheses() {}

    public static void main(String[] args) throws IOException {
        var violations = new ArrayList<String>();
        for (var root : args) {
            if (!Files.isDirectory(Path.of(root))) {
                continue;
            }
            try (var paths = Files.walk(Path.of(root))) {
                for (var path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    check(path, Files.readString(path), violations);
                }
            }
        }
        violations.forEach(System.err::println);
        if (!violations.isEmpty()) {
            System.err.println(
                violations.size() + " line(s) close a parenthesis opened on an earlier line without starting"
                    + " with ')' or '}'. Put the closing parenthesis on its own line."
            );
            System.exit(1);
        }
    }

    static void check(Path path, String source, List<String> violations) {
        var state = State.CODE;
        var line = 1;
        var balance = 0;
        var first = '\0';
        for (var i = 0; i <= source.length(); i++) {
            var c = i < source.length() ? source.charAt(i) : '\n';
            if (c == '\n') {
                if (balance < 0 && first != ')' && first != '}') {
                    violations.add(path + ":" + line);
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
    }
}
