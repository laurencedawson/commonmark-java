package org.commonmark.internal.inline;

import org.commonmark.node.*;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.parser.delimiter.DelimiterRun;

public abstract class EmphasisDelimiterProcessor implements DelimiterProcessor {

    private final char delimiterChar;

    protected EmphasisDelimiterProcessor(char delimiterChar) {
        this.delimiterChar = delimiterChar;
    }

    @Override
    public char getOpeningCharacter() {
        return delimiterChar;
    }

    @Override
    public char getClosingCharacter() {
        return delimiterChar;
    }

    @Override
    public int getMinLength() {
        return 1;
    }

    @Override
    public int process(DelimiterRun openingRun, DelimiterRun closingRun) {
        // "multiple of 3" rule for internal delimiter runs
        if ((openingRun.canClose() || closingRun.canOpen()) &&
                closingRun.originalLength() % 3 != 0 &&
                (openingRun.originalLength() + closingRun.originalLength()) % 3 == 0) {
            return 0;
        }

        int usedDelimiters;
        Node emphasis;
        // calculate actual number of delimiters used from this closer
        if (openingRun.length() >= 2 && closingRun.length() >= 2) {
            usedDelimiters = 2;
            emphasis = new StrongEmphasis(String.valueOf(delimiterChar) + delimiterChar);
        } else {
            usedDelimiters = 1;
            emphasis = new Emphasis(String.valueOf(delimiterChar));
        }

        Text opener = openingRun.getOpener();
        boolean trackSourceSpans = !opener.getSourceSpans().isEmpty();

        SourceSpans sourceSpans = null;
        if (trackSourceSpans) {
            sourceSpans = SourceSpans.empty();
            sourceSpans.addAllFrom(openingRun.getOpeners(usedDelimiters));
        }

        Node closerNode = closingRun.getCloser();
        Node n = opener.getNext();
        while (n != null && n != closerNode) {
            Node next = n.getNext();
            emphasis.appendChild(n);
            if (sourceSpans != null) {
                sourceSpans.addAll(n.getSourceSpans());
            }
            n = next;
        }

        if (sourceSpans != null) {
            sourceSpans.addAllFrom(closingRun.getClosers(usedDelimiters));
            emphasis.setSourceSpans(sourceSpans.getSourceSpans());
        }
        opener.insertAfter(emphasis);

        return usedDelimiters;
    }
}
