import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point.
 *
 * <pre>
 *   java Main lines     A B     # Part A: line diff of A and B
 *   java Main highlight A B     # Part B: the same diff, plus changed-character ranges
 * </pre>
 *
 * <p>Part A prints one prefixed line per move of the edit script: a space to keep a line
 * from both files, {@code -} for a line only in A, {@code +} for a line only in B. Inside
 * a change block — a run of {@code -} and {@code +} with no keep line between — every
 * {@code -} is printed before any {@code +}.
 *
 * <p>Part B prints exactly the same thing, and after each {@code +} line that has a partner
 * it prints one more line of the form
 * <pre>   ? &lt;changed ranges in A&gt; | &lt;changed ranges in B&gt;</pre>
 * Pairing is positional: the 1st {@code -} with the 1st {@code +}, the 2nd with the 2nd,
 * and so on. Left-over lines are unpaired and get no extra line.
 *
 * <p>Files are read as <b>raw bytes</b> and split on {@code \n} only. A trailing newline
 * does not create an extra empty line, an empty file has no lines, and a {@code \r} is kept
 * as part of the line, so {@code a\r\n} and {@code a\n} are different lines. A final
 * incomplete piece is kept. Line identity is byte equality, so files containing bytes that
 * are not valid UTF-8 still diff correctly.
 *
 * <p>Only Part B needs text: ranges count Unicode code points, so lines are decoded as
 * UTF-8 there. Both files are guaranteed valid UTF-8 in highlight tests.
 */
public class Main {

    /** Bad arguments, or a file could not be read. Nothing is written to stdout. */
    private static final int EXIT = 2;

    public static void main(String[] args) {
        if (args.length != 3 || (!"lines".equals(args[0]) && !"highlight".equals(args[0]))) {
            System.err.println("usage: <program> lines|highlight <file-A> <file-B>");
            System.exit(EXIT);
        }
        boolean highlight = "highlight".equals(args[0]);

        // Both files are read before anything reaches stdout, so a failure never
        // leaves a partial diff behind.
        byte[] dataA = readOrExit(args[1]);
        byte[] dataB = readOrExit(args[2]);

        byte[][] linesA = splitLines(dataA);
        byte[][] linesB = splitLines(dataB);

        Map<String, Integer> ids = new HashMap<>();
        byte[] ops = Myers.diff(intern(linesA, ids), intern(linesB, ids));

        try {
            OutputStream out = new BufferedOutputStream(System.out, 1 << 16);
            print(out, linesA, linesB, ops, highlight);
            out.flush();
            System.out.flush();
        } catch (IOException e) {
            System.err.println("error: cannot write output: " + e.getMessage());
            System.exit(1);
        }
    }

    private static byte[] readOrExit(String file) {
        try {
            return Files.readAllBytes(Path.of(file));
        } catch (IOException | RuntimeException e) {
            System.err.println("error: cannot read " + file + ": " + e.getMessage());
            System.exit(EXIT);
            return null;
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * Splits raw bytes on {@code \n} and drops a trailing empty piece, so a final newline
     * adds no phantom line while a blank line in the middle stays.
     */
    static byte[][] splitLines(byte[] data) {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines.add(Arrays.copyOfRange(data, start, i));
                start = i + 1;
            }
        }
        if (start < data.length) {
            lines.add(Arrays.copyOfRange(data, start, data.length));
        }
        return lines.toArray(new byte[0][]);
    }

    /**
     * Maps every distinct line to an integer id, using one table for both files so that
     * equal lines get equal ids. The search then compares two ints per step instead of
     * re-comparing line contents every time the two sequences align.
     */
    private static int[] intern(byte[][] lines, Map<String, Integer> ids) {
        int[] result = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            // ISO-8859-1 maps every byte to exactly one character, so it is a lossless
            // key for byte content: equal bytes give an equal string, and vice versa.
            String key = new String(lines[i], StandardCharsets.ISO_8859_1);
            Integer id = ids.get(key);
            if (id == null) {
                id = ids.size();
                ids.put(key, id);
            }
            result[i] = id;
        }
        return result;
    }

    // ------------------------------------------------------------------ printing

    /**
     * Walks the edit script once, buffering the current change block so that all of its
     * deletions can be printed before any of its insertions.
     */
    private static void print(OutputStream out, byte[][] a, byte[][] b, byte[] ops,
                              boolean highlight) throws IOException {
        int[] deletions = new int[a.length];
        int[] insertions = new int[b.length];
        int deleted = 0;
        int inserted = 0;
        int i = 0;
        int j = 0;

        for (byte op : ops) {
            if (op == Myers.MATCH) {
                flush(out, a, b, deletions, deleted, insertions, inserted, highlight);
                deleted = 0;
                inserted = 0;
                writeLine(out, ' ', a[i]);
                i++;
                j++;
            } else if (op == Myers.DELETE) {
                deletions[deleted++] = i++;
            } else {
                insertions[inserted++] = j++;
            }
        }
        flush(out, a, b, deletions, deleted, insertions, inserted, highlight);
    }

    /**
     * Emits one change block: deletions first, then insertions, in file order. A deletion
     * and an insertion pair up by position, and a paired {@code +} line is followed by its
     * character-range line.
     */
    private static void flush(OutputStream out, byte[][] a, byte[][] b,
                              int[] deletions, int deleted, int[] insertions, int inserted,
                              boolean highlight) throws IOException {
        for (int i = 0; i < deleted; i++) {
            writeLine(out, '-', a[deletions[i]]);
        }
        for (int i = 0; i < inserted; i++) {
            writeLine(out, '+', b[insertions[i]]);
            if (highlight && i < deleted) {
                writeRanges(out, a[deletions[i]], b[insertions[i]]);
            }
        }
    }

    private static void writeLine(OutputStream out, char prefix, byte[] line)
            throws IOException {
        out.write(prefix);
        out.write(line);
        out.write('\n');
    }

    /**
     * Prints {@code ? <ranges in old> | <ranges in new>}.
     *
     * <p>The ranges come from running the same Myers search over the code points of the two
     * lines. Every code point that was not matched is highlighted, which makes the two
     * leftover strings identical by construction and the total count minimal; ranges that
     * touch are merged, and a side with nothing changed prints {@code .}.
     */
    private static void writeRanges(OutputStream out, byte[] oldLine, byte[] newLine)
            throws IOException {
        int[] before = new String(oldLine, StandardCharsets.UTF_8).codePoints().toArray();
        int[] after = new String(newLine, StandardCharsets.UTF_8).codePoints().toArray();
        byte[] ops = Myers.diff(before, after);

        int[] oldChanged = new int[before.length];
        int[] newChanged = new int[after.length];
        int oldCount = 0;
        int newCount = 0;
        int x = 0;
        int y = 0;
        for (byte op : ops) {
            if (op == Myers.MATCH) {
                x++;
                y++;
            } else if (op == Myers.DELETE) {
                oldChanged[oldCount++] = x++;
            } else {
                newChanged[newCount++] = y++;
            }
        }

        out.write('?');
        out.write(' ');
        writeRanges(out, oldChanged, oldCount);
        out.write(' ');
        out.write('|');
        out.write(' ');
        writeRanges(out, newChanged, newCount);
        out.write('\n');
    }

    /** Renders sorted positions as inclusive-exclusive ranges, merging touching ones. */
    private static void writeRanges(OutputStream out, int[] changed, int count)
            throws IOException {
        if (count == 0) {
            out.write('.');
            return;
        }
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < count) {
            int start = changed[i];
            int end = start + 1;
            while (i + 1 < count && changed[i + 1] == end) {
                i++;
                end++;
            }
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(start).append('-').append(end);
            i++;
        }
        out.write(text.toString().getBytes(StandardCharsets.US_ASCII));
    }
}
