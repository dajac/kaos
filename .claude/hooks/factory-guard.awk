# The lexer of factory-guard.sh: reads a shell command line and prints
# the view of it that the guard checks, on one line.
#
#   awk -v view=bare -f factory-guard.awk    the line without its quoted
#       strings and heredoc bodies, which are data (messages, bodies,
#       patterns): what the line-wide checks look at
#   awk -v view=words -f factory-guard.awk   the same, but a quoted
#       string without a space (a single word, "merge") is kept without
#       its quotes: what the per-command checks look at
#
# In both views, backslashes are removed (mer\ge reads as merge), and
# the commands substituted inside double quotes ("$(...)", "`...`") are
# lifted out of their string, between separators, so that they are
# checked like any other command. Heredoc bodies are skipped up to their
# end word; a here-string (<<<) is not a heredoc.

function emit(s) { out = out s }

function close_string(content) {
    if (view == "words" && content !~ /[ \t\n]/) emit(content)
    else emit(" ")
}

# Copies the command substituted at position i of the text (just after
# "$(" or "`"), up to its end, and returns the position after it.
function lift(text, i, closer,    depth, c, buf) {
    depth = 1
    buf = ""
    while (i <= length(text)) {
        c = substr(text, i, 1)
        if (closer == ")" && c == "(") depth++
        if (c == closer) {
            depth--
            if (depth == 0) break
        }
        if (c == "\\") { i++; c = substr(text, i, 1) }
        buf = buf c
        i++
    }
    emit(" ; " buf " ; ")
    return i + 1
}

{ text = text (NR > 1 ? "\n" : "") $0 }

END {
    out = ""
    n = length(text)
    i = 1
    state = "normal"
    content = ""
    pending = ""
    while (i <= n) {
        c = substr(text, i, 1)
        two = substr(text, i, 2)
        if (state == "heredoc") {
            # Skip whole lines until the end word.
            eol = index(substr(text, i), "\n")
            line = eol ? substr(text, i, eol - 1) : substr(text, i)
            stripped = line
            sub(/^\t+/, "", stripped)
            i += eol ? eol : length(line) + 1
            if (line == pending || stripped == pending) { state = "normal"; pending = ""; emit(" ") }
            continue
        }
        if (state == "single") {
            if (c == "'") { close_string(content); state = "normal"; i++; continue }
            content = content c
            i++
            continue
        }
        if (state == "ansi") {
            if (c == "\\") { content = content substr(text, i + 1, 1); i += 2; continue }
            if (c == "'") { close_string(content); state = "normal"; i++; continue }
            content = content c
            i++
            continue
        }
        if (state == "double") {
            if (c == "\\") { content = content substr(text, i + 1, 1); i += 2; continue }
            if (two == "$(") { i = lift(text, i + 2, ")"); content = content " "; continue }
            if (c == "`") { i = lift(text, i + 1, "`"); content = content " "; continue }
            if (c == "\"") { close_string(content); state = "normal"; i++; continue }
            content = content c
            i++
            continue
        }
        # normal
        if (c == "\\") { emit(substr(text, i + 1, 1)); i += 2; continue }
        if (two == "$'") { state = "ansi"; content = ""; i += 2; continue }
        if (c == "'") { state = "single"; content = ""; i++; continue }
        if (c == "\"") { state = "double"; content = ""; i++; continue }
        if (substr(text, i, 3) == "<<<") { emit("<<<"); i += 3; continue }
        if (two == "<<") {
            # A heredoc: note its end word; the body starts at the next line.
            j = i + 2
            if (substr(text, j, 1) == "-") j++
            while (substr(text, j, 1) == " ") j++
            word = ""
            quote = substr(text, j, 1)
            if (quote == "'" || quote == "\"") {
                j++
                while (j <= n && substr(text, j, 1) != quote) { word = word substr(text, j, 1); j++ }
                j++
            } else {
                while (j <= n && substr(text, j, 1) ~ /[A-Za-z0-9_]/) { word = word substr(text, j, 1); j++ }
            }
            if (word != "") pending = word
            emit(" ")
            i = j
            continue
        }
        if (c == "\n") {
            emit(" ; ")
            i++
            if (pending != "") state = "heredoc"
            continue
        }
        emit(c)
        i++
    }
    # An unterminated string is data too.
    if (state == "single" || state == "double" || state == "ansi") close_string(content)
    gsub(/[ \t]+/, " ", out)
    print out
}
