package org.commonmark.test;

import org.commonmark.node.Code;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Map;

public class BlobBenchmarkTest {

    private static final int WARMUP = 100;
    private static final int ITERATIONS = 500;
    private static final int ROUNDS = 5;

    private static final String PLAIN = "Just some plain text with no markdown formatting at all.";
    private static final String SIMPLE = "**bold** and *italic* and `code`";
    private static final String MEDIUM = ""
            + "# Heading 1\n\n"
            + "Some **bold** text with *italic* and `inline code`.\n\n"
            + "## Heading 2\n\n"
            + "> A blockquote with **nested bold**\n\n"
            + "- item one\n- item two\n  - nested item\n- item three\n\n"
            + "1. first\n2. second\n3. third\n\n"
            + "[a link](https://example.com)\n\n"
            + "```\ncode block\nwith lines\n```\n\n"
            + "---\n\n"
            + "~~strikethrough~~ normal text\n";

    private static final String COMPLEX;
    private static final String HEAVY_INLINE;
    private static final String DEEP_NESTING;
    private static final String LONG_DOC;

    static {
        // COMPLEX
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 6; i++) {
                for (int j = 0; j < i; j++) {
                    sb.append('#');
                }
                sb.append(" Heading ").append(i).append("\n\n");
            }
            for (int i = 0; i < 10; i++) {
                sb.append("Paragraph ").append(i)
                        .append(" with **bold**, *italic*, ~~strikethrough~~, `code`, ")
                        .append("and a [link](https://example.com/").append(i).append(").\n\n");
            }
            sb.append("> level 1\n> > level 2\n> > > level 3\n\n");
            for (int i = 0; i < 20; i++) {
                sb.append("- item ").append(i).append("\n");
            }
            sb.append("\n");
            for (int i = 1; i <= 10; i++) {
                sb.append(i).append(". ordered ").append(i).append("\n");
            }
            sb.append("\n```java\npublic class Foo {\n    void bar() {}\n}\n```\n\n");
            sb.append("| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |\n\n");
            sb.append("---\n");
            COMPLEX = sb.toString();
        }

        // HEAVY_INLINE
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append("**b").append(i).append("** *i").append(i).append("* `c").append(i).append("` ");
            }
            HEAVY_INLINE = sb.toString();
        }

        // DEEP_NESTING
        {
            StringBuilder sb = new StringBuilder();
            sb.append("> level 1\n");
            for (int i = 2; i <= 10; i++) {
                for (int j = 0; j < i; j++) {
                    sb.append("> ");
                }
                sb.append("level ").append(i).append("\n");
            }
            DEEP_NESTING = sb.toString();
        }

        // LONG_DOC
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# Introduction\n\n");
            sb.append("This is a long document with many sections, ");
            sb.append("covering a wide range of **markdown** features.\n\n");
            for (int section = 1; section <= 10; section++) {
                sb.append("## Section ").append(section).append("\n\n");
                for (int p = 0; p < 5; p++) {
                    sb.append("Paragraph ").append(p)
                            .append(" with **bold text**, *italic text*, ~~strikethrough~~, ")
                            .append("`inline code`, and a [link](https://example.com/")
                            .append(section).append("/").append(p).append("). ")
                            .append("Some more text to pad this out to a realistic length, ")
                            .append("because real posts tend to have longer paragraphs.\n\n");
                }
                sb.append("> A relevant quote for section ").append(section).append("\n");
                sb.append("> > With nested context\n\n");
                sb.append("- point one\n- point two\n- point three\n  - sub-point\n\n");
                sb.append("```\ncode_example_").append(section).append("()\n```\n\n");
                sb.append("---\n\n");
            }
            sb.append("## Conclusion\n\n");
            sb.append("Final paragraph with ^superscript^ and ~subscript~ for good measure.\n");
            LONG_DOC = sb.toString();
        }
    }

    private static final MethodHandle GET_THREAD_ALLOC;
    private static final Object THREAD_MX_BEAN;

    static {
        MethodHandle h = null;
        Object bean = null;
        try {
            Class<?> mf = Class.forName("java.lang.management.ManagementFactory");
            bean = mf.getMethod("getThreadMXBean").invoke(null);
            // Use the com.sun.management.ThreadMXBean interface method via reflection
            Class<?> sunBean = Class.forName("com.sun.management.ThreadMXBean");
            java.lang.reflect.Method m = sunBean.getMethod("getThreadAllocatedBytes", long.class);
            h = MethodHandles.lookup().unreflect(m);
        } catch (Exception e) {
            // Allocation tracking not available on this JVM
        }
        GET_THREAD_ALLOC = h;
        THREAD_MX_BEAN = bean;
    }

    private static long getAllocatedBytes() {
        if (GET_THREAD_ALLOC == null) {
            return -1;
        }
        try {
            return (long) GET_THREAD_ALLOC.invoke(THREAD_MX_BEAN, Thread.currentThread().getId());
        } catch (Throwable e) {
            return -1;
        }
    }

    private Parser createParser() {
        return Parser.builder().build();
    }

    /**
     * Returns {medianTimeUs, bytesPerIteration}.
     */
    private double[] benchmarkParse(Parser parser, String input) {
        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            parser.parse(input);
        }

        double[] times = new double[ROUNDS];
        long[] allocs = new long[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long allocBefore = getAllocatedBytes();
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                parser.parse(input);
            }
            long elapsed = System.nanoTime() - start;
            long allocAfter = getAllocatedBytes();
            times[r] = elapsed / 1000.0 / ITERATIONS;
            allocs[r] = (allocAfter - allocBefore) / ITERATIONS;
        }
        Arrays.sort(times);
        Arrays.sort(allocs);
        return new double[]{times[ROUNDS / 2], allocs[ROUNDS / 2]};
    }

    /**
     * Count exact allocations for a single parse using ThreadMXBean byte delta
     * divided by a single iteration. Run with a single parse to get precise per-parse bytes.
     */
    @Test
    public void allocProfile() {
        Parser parser = createParser();

        String[][] inputs = {
                {"simple", SIMPLE},
                {"medium", MEDIUM},
                {"heavy-inline", HEAVY_INLINE},
                {"long-doc", LONG_DOC},
        };

        // Warmup all
        for (String[] entry : inputs) {
            for (int i = 0; i < 100; i++) {
                parser.parse(entry[1]);
            }
        }

        System.out.println("\n=== Allocation Profile (single parse) ===");
        for (String[] entry : inputs) {
            String name = entry[0];
            String input = entry[1];

            // Run 10 single-parse measurements and take median
            long[] bytes = new long[10];
            for (int r = 0; r < 10; r++) {
                long before = getAllocatedBytes();
                parser.parse(input);
                bytes[r] = getAllocatedBytes() - before;
            }
            Arrays.sort(bytes);
            long medianBytes = bytes[5];
            // Estimate object count: assume average 40 bytes per object (16 header + 24 payload on compressed oops)
            long estObjects = medianBytes / 40;
            int inputBytes = input.length() * 2; // Java chars are 2 bytes
            double ratio = (double) medianBytes / inputBytes;
            System.out.printf("%s (%d chars, %d B input): %d B alloc, %.1fx input, ~%d objects%n",
                    name, input.length(), inputBytes, medianBytes, ratio, estObjects);
        }
        System.out.println("=========================================");
    }

    /**
     * Count AST nodes and their types after parsing, to understand what portion of
     * allocations are inherent output vs overhead.
     */
    @Test
    public void nodeCount() {
        Parser parser = createParser();

        String[][] inputs = {
                {"simple", SIMPLE},
                {"medium", MEDIUM},
                {"complex", COMPLEX},
                {"heavy-inline", HEAVY_INLINE},
                {"long-doc", LONG_DOC},
        };

        System.out.println("\n=== AST Node Count ===");
        for (String[] entry : inputs) {
            String name = entry[0];
            String input = entry[1];
            Node doc = parser.parse(input);

            Map<String, Integer> counts = new java.util.TreeMap<>();
            int totalNodes = 0;
            int totalStringBytes = 0;
            Node node = doc;
            while (node != null) {
                totalNodes++;
                String type = node.getClass().getSimpleName();
                counts.merge(type, 1, Integer::sum);
                if (node instanceof Text) {
                    totalStringBytes += ((Text) node).getLiteral().length() * 2;
                } else if (node instanceof Code) {
                    totalStringBytes += ((Code) node).getLiteral().length() * 2;
                }

                // Depth-first traversal
                if (node.getFirstChild() != null) {
                    node = node.getFirstChild();
                } else if (node.getNext() != null) {
                    node = node.getNext();
                } else {
                    // Walk up to find next sibling
                    node = node.getParent();
                    while (node != null && node.getNext() == null) {
                        node = node.getParent();
                    }
                    if (node != null) {
                        node = node.getNext();
                    }
                }
            }

            System.out.printf("%s (%d chars): %d nodes, %d B in strings%n", name, input.length(), totalNodes, totalStringBytes);
            counts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(e -> System.out.printf("  %4d × %s%n", e.getValue(), e.getKey()));
        }
        System.out.println("======================");
    }

    /**
     * Break down allocations by phase: block parsing vs inline parsing vs post-processing.
     */
    @Test
    public void allocBreakdown() {
        // We can't easily separate phases since parse() does everything.
        // But we can compare parse() vs parseInline() to measure block parser overhead.
        Parser parser = createParser();

        String[][] inputs = {
                {"complex", COMPLEX},
                {"long-doc", LONG_DOC},
        };

        // Warmup
        for (String[] entry : inputs) {
            for (int i = 0; i < 100; i++) {
                parser.parse(entry[1]);
            }
        }

        System.out.println("\n=== Allocation Breakdown ===");
        for (String[] entry : inputs) {
            String name = entry[0];
            String input = entry[1];

            // Measure full parse
            long[] fullBytes = new long[10];
            for (int r = 0; r < 10; r++) {
                long before = getAllocatedBytes();
                parser.parse(input);
                fullBytes[r] = getAllocatedBytes() - before;
            }
            Arrays.sort(fullBytes);
            long fullMedian = fullBytes[5];

            // Measure just inline parsing (split into lines, parse each as inline)
            String[] lines = input.split("\n");
            long[] inlineBytes = new long[10];
            for (int r = 0; r < 10; r++) {
                long before = getAllocatedBytes();
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        parser.parseInline(line);
                    }
                }
                inlineBytes[r] = getAllocatedBytes() - before;
            }
            Arrays.sort(inlineBytes);
            long inlineMedian = inlineBytes[5];

            System.out.printf("%s: full parse %d B, inline-only %d B, block overhead ~%d B%n",
                    name, fullMedian, inlineMedian, fullMedian - inlineMedian);
        }
        System.out.println("============================");
    }

    @Test
    public void benchAll() {
        Parser parser = createParser();

        String[][] inputs = {
                {"plain", PLAIN},
                {"simple", SIMPLE},
                {"medium", MEDIUM},
                {"complex", COMPLEX},
                {"heavy-inline", HEAVY_INLINE},
                {"deep-nesting", DEEP_NESTING},
                {"long-doc", LONG_DOC},
        };

        System.out.println("=== Benchmark Results ===");
        for (String[] entry : inputs) {
            String name = entry[0];
            String input = entry[1];
            double[] result = benchmarkParse(parser, input);
            System.out.printf("%s (%d chars): %.1f us, %d B/iter%n",
                    name, input.length(), result[0], (long) result[1]);
        }
        System.out.println("=========================");
    }

    /**
     * Benchmark parseParagraphs() fast path for multi-paragraph inputs that have no block syntax.
     */
    @Test
    public void benchParagraphs() {
        Parser parser = createParser();

        // These inputs contain only paragraphs — no headings, lists, blockquotes, code blocks
        String paragraphsOnly = ""
                + "First paragraph with **bold** and *italic* and `code`.\n\n"
                + "Second paragraph with [a link](https://example.com) and more text.\n\n"
                + "Third paragraph just plain text with no formatting at all.\n\n"
                + "Fourth paragraph with **nested *emphasis* inside bold** text.\n\n"
                + "Fifth paragraph ending the document.";

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            parser.parseParagraphs(paragraphsOnly);
        }

        System.out.println("=== parseParagraphs() Fast Path ===");
        double[] times = new double[ROUNDS];
        long[] allocs = new long[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long allocBefore = getAllocatedBytes();
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                parser.parseParagraphs(paragraphsOnly);
            }
            long elapsed = System.nanoTime() - start;
            long allocAfter = getAllocatedBytes();
            times[r] = elapsed / 1000.0 / ITERATIONS;
            allocs[r] = (allocAfter - allocBefore) / ITERATIONS;
        }
        Arrays.sort(times);
        Arrays.sort(allocs);
        System.out.printf("paragraphs-only (%d chars): %.1f us, %d B/iter%n",
                paragraphsOnly.length(), times[ROUNDS / 2], allocs[ROUNDS / 2]);

        // Compare with parse()
        for (int i = 0; i < WARMUP; i++) {
            parser.parse(paragraphsOnly);
        }
        for (int r = 0; r < ROUNDS; r++) {
            long allocBefore = getAllocatedBytes();
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                parser.parse(paragraphsOnly);
            }
            long elapsed = System.nanoTime() - start;
            long allocAfter = getAllocatedBytes();
            times[r] = elapsed / 1000.0 / ITERATIONS;
            allocs[r] = (allocAfter - allocBefore) / ITERATIONS;
        }
        Arrays.sort(times);
        Arrays.sort(allocs);
        System.out.printf("paragraphs-only via parse() (%d chars): %.1f us, %d B/iter%n",
                paragraphsOnly.length(), times[ROUNDS / 2], allocs[ROUNDS / 2]);
        System.out.println("===================================");
    }

    /**
     * Benchmark parseInline() fast path for single-paragraph inputs.
     */
    @Test
    public void benchInline() {
        Parser parser = createParser();

        String[][] inputs = {
                {"plain", PLAIN},
                {"simple", SIMPLE},
                {"heavy-inline", HEAVY_INLINE},
        };

        // Warmup
        for (String[] entry : inputs) {
            for (int i = 0; i < WARMUP; i++) {
                parser.parseInline(entry[1]);
            }
        }

        System.out.println("=== parseInline() Fast Path ===");
        for (String[] entry : inputs) {
            String name = entry[0];
            String input = entry[1];

            double[] times = new double[ROUNDS];
            long[] allocs = new long[ROUNDS];
            for (int r = 0; r < ROUNDS; r++) {
                long allocBefore = getAllocatedBytes();
                long start = System.nanoTime();
                for (int i = 0; i < ITERATIONS; i++) {
                    parser.parseInline(input);
                }
                long elapsed = System.nanoTime() - start;
                long allocAfter = getAllocatedBytes();
                times[r] = elapsed / 1000.0 / ITERATIONS;
                allocs[r] = (allocAfter - allocBefore) / ITERATIONS;
            }
            Arrays.sort(times);
            Arrays.sort(allocs);
            System.out.printf("%s (%d chars): %.1f us, %d B/iter%n",
                    name, input.length(), times[ROUNDS / 2], allocs[ROUNDS / 2]);
        }
        System.out.println("===============================");
    }
}
