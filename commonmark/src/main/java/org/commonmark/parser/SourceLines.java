package org.commonmark.parser;

import org.commonmark.node.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of lines ({@link SourceLine}) from the input source.
 *
 * @since 0.16.0
 */
public class SourceLines {

    private List<SourceLine> lines;

    public static SourceLines empty() {
        return new SourceLines(new ArrayList<>());
    }

    public static SourceLines of(SourceLine sourceLine) {
        var lines = new ArrayList<SourceLine>(1);
        lines.add(sourceLine);
        return new SourceLines(lines);
    }

    public static SourceLines of(List<SourceLine> sourceLines) {
        return new SourceLines(new ArrayList<>(sourceLines));
    }

    /**
     * Wrap an existing list without copying. The caller must not modify the list after this call.
     */
    public static SourceLines wrap(List<SourceLine> sourceLines) {
        return new SourceLines(sourceLines);
    }

    private SourceLines(List<SourceLine> lines) {
        this.lines = lines;
    }

    public void addLine(SourceLine sourceLine) {
        lines.add(sourceLine);
    }

    public List<SourceLine> getLines() {
        return lines;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public String getContent() {
        if (lines.size() == 1) {
            return lines.get(0).getContent().toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i != 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i).getContent());
        }
        return sb.toString();
    }

    public List<SourceSpan> getSourceSpans() {
        List<SourceSpan> sourceSpans = new ArrayList<>();
        for (SourceLine line : lines) {
            SourceSpan sourceSpan = line.getSourceSpan();
            if (sourceSpan != null) {
                sourceSpans.add(sourceSpan);
            }
        }
        return sourceSpans;
    }
}
