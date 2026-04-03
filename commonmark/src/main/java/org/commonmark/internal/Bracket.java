package org.commonmark.internal;

import org.commonmark.node.Text;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;

/**
 * Opening bracket for links ({@code [}), images ({@code ![}), or links with other markers.
 */
public class Bracket {

    public final Text markerNode;
    public final long markerPosition;
    public final Text bracketNode;
    public final long bracketPosition;
    public final long contentPosition;
    public final Bracket previous;
    public final Delimiter previousDelimiter;
    public boolean allowed = true;
    public boolean bracketAfter = false;

    static public Bracket link(Text bracketNode, long bracketPosition, long contentPosition, Bracket previous, Delimiter previousDelimiter) {
        return new Bracket(null, -1, bracketNode, bracketPosition, contentPosition, previous, previousDelimiter);
    }

    static public Bracket withMarker(Text markerNode, long markerPosition, Text bracketNode, long bracketPosition, long contentPosition, Bracket previous, Delimiter previousDelimiter) {
        return new Bracket(markerNode, markerPosition, bracketNode, bracketPosition, contentPosition, previous, previousDelimiter);
    }

    private Bracket(Text markerNode, long markerPosition, Text bracketNode, long bracketPosition, long contentPosition, Bracket previous, Delimiter previousDelimiter) {
        this.markerNode = markerNode;
        this.markerPosition = markerPosition;
        this.bracketNode = bracketNode;
        this.bracketPosition = bracketPosition;
        this.contentPosition = contentPosition;
        this.previous = previous;
        this.previousDelimiter = previousDelimiter;
    }
}
