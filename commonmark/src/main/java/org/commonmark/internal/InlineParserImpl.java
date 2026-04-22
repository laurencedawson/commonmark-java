package org.commonmark.internal;

import org.commonmark.internal.inline.*;
import org.commonmark.internal.util.Escaping;
import org.commonmark.internal.util.LinkScanner;
import org.commonmark.node.*;
import org.commonmark.parser.InlineParser;
import org.commonmark.parser.InlineParserContext;
import org.commonmark.parser.SourceLines;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.parser.beta.*;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.text.Characters;

import java.util.*;

public class InlineParserImpl implements InlineParser, InlineParserState {

    private final InlineParserContext context;
    private final List<InlineContentParserFactory> inlineContentParserFactories;
    private final Map<Character, DelimiterProcessor> delimiterProcessors;
    // Array-based lookup for delimiter processors (avoids HashMap + autoboxing in hot path)
    private final DelimiterProcessor[] delimiterProcessorsByChar;
    private final List<LinkProcessor> linkProcessors;
    private final boolean[] specialCharacters;
    private final boolean[] linkMarkers;

    // Map from trigger character to list of parsers. The map structure is built once; on reset() we
    // replace parser instances in-place (since parsers can be stateful and need fresh instances per block).
    private final Map<Character, List<InlineContentParser>> inlineParsers;
    // Array-based lookup for inline parsers (avoids HashMap + autoboxing in hot path)
    @SuppressWarnings("unchecked")
    private final List<InlineContentParser>[] inlineParsersByChar = new List[128];
    private Scanner scanner;
    private boolean includeSourceSpans;
    private int trailingSpaces;

    /**
     * Top delimiter (emphasis, strong emphasis or custom emphasis). (Brackets are on a separate stack, different
     * from the algorithm described in the spec.)
     */
    private Delimiter lastDelimiter;

    /**
     * Top opening bracket (<code>[</code> or <code>![)</code>).
     */
    private Bracket lastBracket;

    public InlineParserImpl(InlineParserContext context) {
        this.context = context;
        this.inlineContentParserFactories = calculateInlineContentParserFactories(context.getCustomInlineContentParserFactories());
        this.delimiterProcessors = calculateDelimiterProcessors(context.getCustomDelimiterProcessors());
        this.linkProcessors = calculateLinkProcessors(context.getCustomLinkProcessors());
        this.linkMarkers = calculateLinkMarkers(context.getCustomLinkMarkers());
        this.specialCharacters = calculateSpecialCharacters(linkMarkers, this.delimiterProcessors.keySet(), this.inlineContentParserFactories);

        // Build array-based delimiter processor lookup for hot path
        this.delimiterProcessorsByChar = new DelimiterProcessor[128];
        for (var entry : this.delimiterProcessors.entrySet()) {
            char c = entry.getKey();
            if (c < 128) {
                delimiterProcessorsByChar[c] = entry.getValue();
            }
        }

        // Build the inline parser map structure once. On reset(), we replace parser instances in-place.
        this.inlineParsers = new HashMap<>();
        for (var factory : inlineContentParserFactories) {
            var parser = factory.create();
            for (var c : factory.getTriggerCharacters()) {
                inlineParsers.computeIfAbsent(c, k -> new ArrayList<>()).add(parser);
            }
        }
        // Populate array-based lookup
        for (var entry : inlineParsers.entrySet()) {
            char c = entry.getKey();
            if (c < 128) {
                inlineParsersByChar[c] = entry.getValue();
            }
        }
    }

    private List<InlineContentParserFactory> calculateInlineContentParserFactories(List<InlineContentParserFactory> customFactories) {
        // Custom parsers can override built-in parsers if they want, so make sure they are tried first
        var list = new ArrayList<>(customFactories);
        list.add(new BackslashInlineParser.Factory());
        list.add(new BackticksInlineParser.Factory());
        list.add(new EntityInlineParser.Factory());
        list.add(new AutolinkInlineParser.Factory());
        list.add(new HtmlInlineParser.Factory());
        return list;
    }

    private List<LinkProcessor> calculateLinkProcessors(List<LinkProcessor> linkProcessors) {
        // Custom link processors can override the built-in behavior, so make sure they are tried first
        var list = new ArrayList<>(linkProcessors);
        list.add(new CoreLinkProcessor());
        return list;
    }

    private static Map<Character, DelimiterProcessor> calculateDelimiterProcessors(List<DelimiterProcessor> delimiterProcessors) {
        var map = new HashMap<Character, DelimiterProcessor>();
        addDelimiterProcessors(List.of(new AsteriskDelimiterProcessor(), new UnderscoreDelimiterProcessor()), map);
        addDelimiterProcessors(delimiterProcessors, map);
        return map;
    }

    private static void addDelimiterProcessors(Iterable<DelimiterProcessor> delimiterProcessors, Map<Character, DelimiterProcessor> map) {
        for (DelimiterProcessor delimiterProcessor : delimiterProcessors) {
            char opening = delimiterProcessor.getOpeningCharacter();
            char closing = delimiterProcessor.getClosingCharacter();
            if (opening == closing) {
                DelimiterProcessor old = map.get(opening);
                if (old != null && old.getOpeningCharacter() == old.getClosingCharacter()) {
                    StaggeredDelimiterProcessor s;
                    if (old instanceof StaggeredDelimiterProcessor) {
                        s = (StaggeredDelimiterProcessor) old;
                    } else {
                        s = new StaggeredDelimiterProcessor(opening);
                        s.add(old);
                    }
                    s.add(delimiterProcessor);
                    map.put(opening, s);
                } else {
                    addDelimiterProcessorForChar(opening, delimiterProcessor, map);
                }
            } else {
                addDelimiterProcessorForChar(opening, delimiterProcessor, map);
                addDelimiterProcessorForChar(closing, delimiterProcessor, map);
            }
        }
    }

    private static void addDelimiterProcessorForChar(char delimiterChar, DelimiterProcessor toAdd, Map<Character, DelimiterProcessor> delimiterProcessors) {
        DelimiterProcessor existing = delimiterProcessors.put(delimiterChar, toAdd);
        if (existing != null) {
            throw new IllegalArgumentException("Delimiter processor conflict with delimiter char '" + delimiterChar + "'");
        }
    }

    private static boolean[] calculateLinkMarkers(Set<Character> linkMarkers) {
        var arr = new boolean[128];
        for (var c : linkMarkers) {
            if (c < 128) {
                arr[c] = true;
            }
        }
        arr['!'] = true;
        return arr;
    }

    private static boolean[] calculateSpecialCharacters(boolean[] linkMarkers,
                                                        Set<Character> delimiterCharacters,
                                                        List<InlineContentParserFactory> inlineContentParserFactories) {
        var arr = new boolean[128];
        System.arraycopy(linkMarkers, 0, arr, 0, 128);
        for (Character c : delimiterCharacters) {
            if (c < 128) {
                arr[c] = true;
            }
        }
        for (var factory : inlineContentParserFactories) {
            for (var c : factory.getTriggerCharacters()) {
                if (c < 128) {
                    arr[c] = true;
                }
            }
        }
        arr['['] = true;
        arr[']'] = true;
        arr['!'] = true;
        arr['\n'] = true;
        return arr;
    }

    /**
     * Reset parser instances in the existing inlineParsers map. The map structure and list sizes
     * stay the same, only the parser objects are replaced (since they can be stateful).
     */
    private void resetInlineContentParsers() {
        // Clear all lists first, then re-add in the same order as the constructor
        for (var list : inlineParsers.values()) {
            list.clear();
        }
        for (var factory : inlineContentParserFactories) {
            var parser = factory.create();
            for (var c : factory.getTriggerCharacters()) {
                inlineParsers.get(c).add(parser);
            }
        }
    }

    @Override
    public Scanner scanner() {
        return scanner;
    }

    /**
     * Parse content in block into inline children, appending them to the block node.
     */
    @Override
    public void parse(SourceLines lines, Node block) {
        reset(lines);

        while (true) {
            if (!parseInline(block)) {
                break;
            }
        }

        processDelimiters(null);
        mergeChildTextNodes(block);
    }

    void reset(SourceLines lines) {
        if (this.scanner == null) {
            this.scanner = Scanner.of(lines);
        } else {
            this.scanner.reset(lines.getLines());
        }
        this.includeSourceSpans = !lines.getSourceSpans().isEmpty();
        this.trailingSpaces = 0;
        this.lastDelimiter = null;
        this.lastBracket = null;
        resetInlineContentParsers();
    }

    private Text text(Position start, Position end) {
        Text text = new Text(scanner.getContentBetween(start, end));
        if (includeSourceSpans) {
            text.setSourceSpans(scanner.getSource(start, end).getSourceSpans());
        }
        return text;
    }

    /**
     * Parse the next inline element in subject, advancing our position.
     * Appends result directly to block. Returns false at end of input.
     */
    private boolean parseInline(Node block) {
        char c = scanner.peek();

        switch (c) {
            case '[':
                block.appendNewChild(parseOpenBracket());
                return true;
            case ']':
                block.appendNewChild(parseCloseBracket());
                return true;
            case '\n':
                block.appendNewChild(parseLineBreak());
                return true;
            case Scanner.END:
                return false;
        }

        if (c < 128 && linkMarkers[c]) {
            long markerPos = scanner.positionAsLong();
            if (parseLinkMarker(block)) {
                return true;
            }
            scanner.setPositionFromLong(markerPos);
        }

        if (!(c < 128 && specialCharacters[c])) {
            block.appendNewChild(parseText());
            return true;
        }

        List<InlineContentParser> inlineParsers = c < 128 ? inlineParsersByChar[c] : this.inlineParsers.get(c);
        if (inlineParsers != null) {
            long position = scanner.positionAsLong();
            for (InlineContentParser inlineParser : inlineParsers) {
                ParsedInline parsedInline = inlineParser.tryParse(this);
                if (parsedInline instanceof ParsedInlineImpl) {
                    ParsedInlineImpl parsedInlineImpl = (ParsedInlineImpl) parsedInline;
                    Node node = parsedInlineImpl.getNode();
                    scanner.setPosition(parsedInlineImpl.getPosition());
                    if (includeSourceSpans && node.getSourceSpans().isEmpty()) {
                        node.setSourceSpans(scanner.getSource(Scanner.positionFromLong(position), scanner.position()).getSourceSpans());
                    }
                    block.appendNewChild(node);
                    return true;
                } else {
                    scanner.setPositionFromLong(position);
                }
            }
        }

        DelimiterProcessor delimiterProcessor = c < 128 ? delimiterProcessorsByChar[c] : delimiterProcessors.get(c);
        if (delimiterProcessor != null) {
            if (parseDelimiters(block, delimiterProcessor, c)) {
                return true;
            }
        }

        block.appendNewChild(parseText());
        return true;
    }

    private boolean parseDelimiters(Node block, DelimiterProcessor delimiterProcessor, char delimiterChar) {
        if (!scanDelimiters(delimiterProcessor, delimiterChar)) {
            return false;
        }

        lastDelimiter = new Delimiter(lastDelimChars, delimiterChar, lastDelimCanOpen, lastDelimCanClose, lastDelimiter);
        if (lastDelimiter.previous != null) {
            lastDelimiter.previous.next = lastDelimiter;
        }

        for (Text text : lastDelimChars) {
            block.appendNewChild(text);
        }
        return true;
    }

    /**
     * Add open bracket to delimiter stack and add a text node to block's children.
     */
    private Node parseOpenBracket() {
        long start = scanner.positionAsLong();
        scanner.next();
        long contentPosition = scanner.positionAsLong();

        Text node;
        if (includeSourceSpans) {
            node = text(Scanner.positionFromLong(start), Scanner.positionFromLong(contentPosition));
        } else {
            node = new Text(scanner.getContentBetweenLong(start, contentPosition));
        }

        addBracket(Bracket.link(node, start, contentPosition, lastBracket, lastDelimiter));

        return node;
    }

    private boolean parseLinkMarker(Node block) {
        long markerPosition = scanner.positionAsLong();
        scanner.next();
        long bracketPosition = scanner.positionAsLong();
        if (scanner.next('[')) {
            long contentPosition = scanner.positionAsLong();
            Text bangNode, bracketNode;
            if (includeSourceSpans) {
                bangNode = text(Scanner.positionFromLong(markerPosition), Scanner.positionFromLong(bracketPosition));
                bracketNode = text(Scanner.positionFromLong(bracketPosition), Scanner.positionFromLong(contentPosition));
            } else {
                bangNode = new Text(scanner.getContentBetweenLong(markerPosition, bracketPosition));
                bracketNode = new Text(scanner.getContentBetweenLong(bracketPosition, contentPosition));
            }

            addBracket(Bracket.withMarker(bangNode, markerPosition, bracketNode, bracketPosition, contentPosition, lastBracket, lastDelimiter));
            block.appendNewChild(bangNode);
            block.appendNewChild(bracketNode);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Try to match close bracket against an opening in the delimiter stack. Return either a link or image, or a
     * plain [ character. If there is a matching delimiter, remove it from the delimiter stack.
     */
    private Node parseCloseBracket() {
        long beforeClose = scanner.positionAsLong();
        scanner.next();
        long afterClose = scanner.positionAsLong();

        // Get previous `[` or `![`
        Bracket opener = lastBracket;
        if (opener == null) {
            return textFromLong(beforeClose, afterClose);
        }

        if (!opener.allowed) {
            removeLastBracket();
            return textFromLong(beforeClose, afterClose);
        }

        var linkOrImage = parseLinkOrImage(opener, beforeClose);
        if (linkOrImage != null) {
            return linkOrImage;
        }
        scanner.setPositionFromLong(afterClose);

        removeLastBracket();
        return textFromLong(beforeClose, afterClose);
    }

    /**
     * Create a Text node from long-encoded positions. Includes source spans if enabled.
     */
    private Text textFromLong(long start, long end) {
        Text text = new Text(scanner.getContentBetweenLong(start, end));
        if (includeSourceSpans) {
            text.setSourceSpans(scanner.getSource(Scanner.positionFromLong(start), Scanner.positionFromLong(end)).getSourceSpans());
        }
        return text;
    }

    private Node parseLinkOrImage(Bracket opener, long beforeClose) {
        var linkInfo = parseLinkInfo(opener, beforeClose);
        if (linkInfo == null) {
            return null;
        }
        long processorStartPosition = scanner.positionAsLong();

        for (var linkProcessor : linkProcessors) {
            var linkResult = linkProcessor.process(linkInfo, scanner, context);
            if (!(linkResult instanceof LinkResultImpl)) {
                scanner.setPositionFromLong(processorStartPosition);
                continue;
            }

            var result = (LinkResultImpl) linkResult;
            var node = result.getNode();
            var position = result.getPosition();
            var includeMarker = result.isIncludeMarker();

            switch (result.getType()) {
                case WRAP:
                    scanner.setPosition(position);
                    return wrapBracket(opener, node, includeMarker);
                case REPLACE:
                    scanner.setPosition(position);
                    return replaceBracket(opener, node, includeMarker);
            }
        }

        return null;
    }

    private LinkInfo parseLinkInfo(Bracket opener, long beforeClose) {
        // Starting position is after the closing `]`
        long afterClose = scanner.positionAsLong();
        Position afterClosePos = null; // Lazy — only create if we need to return a LinkInfo

        // Maybe an inline link/image
        if (parseInlineDestinationTitle()) {
            var text = scanner.getContentBetweenLong(opener.contentPosition, beforeClose);
            afterClosePos = Scanner.positionFromLong(afterClose);
            return new LinkInfoImpl(opener.markerNode, opener.bracketNode, text, null, lastDestination, lastTitle, afterClosePos);
        }
        scanner.setPositionFromLong(afterClose);

        // Maybe a reference link/image like `[foo][bar]`, `[foo][]` or `[foo]`.
        // Note that even `[foo](` could be a valid link if foo is a reference, which is why we try this even if the `(`
        // failed to be parsed as an inline link/image before.

        // See if there's a link label like `[bar]` or `[]`
        var label = parseLinkLabel(scanner);
        if (label == null) {
            scanner.setPositionFromLong(afterClose);
        }
        var textIsReference = label == null || label.isEmpty();
        if (opener.bracketAfter && textIsReference && opener.markerNode == null) {
            return null;
        }

        var text = scanner.getContentBetweenLong(opener.contentPosition, beforeClose);
        if (afterClosePos == null) {
            afterClosePos = Scanner.positionFromLong(afterClose);
        }
        return new LinkInfoImpl(opener.markerNode, opener.bracketNode, text, label, null, null, afterClosePos);
    }

    private Node wrapBracket(Bracket opener, Node wrapperNode, boolean includeMarker) {
        // Add all nodes between the opening bracket and now (closing bracket) as child nodes of the link
        Node n = opener.bracketNode.getNext();
        while (n != null) {
            Node next = n.getNext();
            wrapperNode.appendChild(n);
            n = next;
        }

        if (includeSourceSpans) {
            long startPos = includeMarker && opener.markerPosition >= 0 ? opener.markerPosition : opener.bracketPosition;
            wrapperNode.setSourceSpans(scanner.getSource(Scanner.positionFromLong(startPos), scanner.position()).getSourceSpans());
        }

        // Process delimiters such as emphasis inside link/image
        processDelimiters(opener.previousDelimiter);
        mergeChildTextNodes(wrapperNode);
        // We don't need the corresponding text node anymore, we turned it into a link/image node
        if (includeMarker && opener.markerNode != null) {
            opener.markerNode.unlink();
        }
        opener.bracketNode.unlink();
        removeLastBracket();

        // Links within links are not allowed. We found this link, so there can be no other links around it.
        if (opener.markerNode == null) {
            disallowPreviousLinks();
        }

        return wrapperNode;
    }

    private Node replaceBracket(Bracket opener, Node node, boolean includeMarker) {
        // Remove delimiters (but keep text nodes)
        while (lastDelimiter != null && lastDelimiter != opener.previousDelimiter) {
            removeDelimiterKeepNode(lastDelimiter);
        }

        if (includeSourceSpans) {
            long startPos = includeMarker && opener.markerPosition >= 0 ? opener.markerPosition : opener.bracketPosition;
            node.setSourceSpans(scanner.getSource(Scanner.positionFromLong(startPos), scanner.position()).getSourceSpans());
        }

        removeLastBracket();

        // Remove nodes that we added since the opener, because we're replacing them
        Node n = includeMarker && opener.markerNode != null ? opener.markerNode : opener.bracketNode;
        while (n != null) {
            var next = n.getNext();
            n.unlink();
            n = next;
        }

        // Links within links are not allowed. We found this link, so there can be no other links around it.
        // Note that this makes any syntax like `[foo]` behave the same as built-in links, which is probably a good
        // default (it works for footnotes). It might be useful for a `LinkProcessor` to be able to specify the
        // behavior; something we could add to `LinkResult` in the future if requested.
        if (opener.markerNode == null || !includeMarker) {
            disallowPreviousLinks();
        }

        return node;
    }

    private void addBracket(Bracket bracket) {
        if (lastBracket != null) {
            lastBracket.bracketAfter = true;
        }
        lastBracket = bracket;
    }

    private void removeLastBracket() {
        lastBracket = lastBracket.previous;
    }

    private void disallowPreviousLinks() {
        Bracket bracket = lastBracket;
        while (bracket != null) {
            if (bracket.markerNode == null) {
                // Disallow link opener. It will still get matched, but will not result in a link.
                bracket.allowed = false;
            }
            bracket = bracket.previous;
        }
    }

    private boolean parseInlineDestinationTitle() {
        if (!scanner.next('(')) {
            return false;
        }

        scanner.whitespace();
        String dest = parseLinkDestination(scanner);
        if (dest == null) {
            return false;
        }

        String title = null;
        int whitespace = scanner.whitespace();
        if (whitespace >= 1) {
            title = parseLinkTitle(scanner);
            scanner.whitespace();
        }
        if (!scanner.next(')')) {
            return false;
        }
        lastDestination = dest;
        lastTitle = title;
        return true;
    }

    /**
     * Attempt to parse link destination, returning the string or null if no match.
     */
    private static String parseLinkDestination(Scanner scanner) {
        char delimiter = scanner.peek();
        long start = scanner.positionAsLong();
        if (!LinkScanner.scanLinkDestination(scanner)) {
            return null;
        }

        String dest;
        if (delimiter == '<') {
            String rawDestination = scanner.getContentBetweenLong(start, scanner.positionAsLong());
            dest = rawDestination.substring(1, rawDestination.length() - 1);
        } else {
            dest = scanner.getContentBetweenLong(start, scanner.positionAsLong());
        }

        return Escaping.unescapeString(dest);
    }

    private static String parseLinkTitle(Scanner scanner) {
        long start = scanner.positionAsLong();
        if (!LinkScanner.scanLinkTitle(scanner)) {
            return null;
        }

        String rawTitle = scanner.getContentBetweenLong(start, scanner.positionAsLong());
        return Escaping.unescapeString(rawTitle.substring(1, rawTitle.length() - 1));
    }

    /**
     * Attempt to parse a link label, returning the label between the brackets or null.
     */
    static String parseLinkLabel(Scanner scanner) {
        if (!scanner.next('[')) {
            return null;
        }

        long start = scanner.positionAsLong();
        if (!LinkScanner.scanLinkLabelContent(scanner)) {
            return null;
        }
        long end = scanner.positionAsLong();

        if (!scanner.next(']')) {
            return null;
        }

        String content = scanner.getContentBetweenLong(start, end);
        // spec: A link label can have at most 999 characters inside the square brackets.
        if (content.length() > 999) {
            return null;
        }

        return content;
    }

    private Node parseLineBreak() {
        scanner.next();

        var hard = trailingSpaces >= 2;
        trailingSpaces = 0;
        if (hard) {
            return new HardLineBreak();
        } else {
            return new SoftLineBreak();
        }
    }

    /**
     * Parse the next character as plain text, and possibly more if the following characters are non-special.
     */
    private Node parseText() {
        long start = scanner.positionAsLong();
        scanner.next();
        char c;
        while (true) {
            c = scanner.peek();
            if (c == Scanner.END || (c < 128 && specialCharacters[c])) {
                break;
            }
            scanner.next();
        }

        long endPos = scanner.positionAsLong();
        String content = scanner.getContentBetweenLong(start, endPos);

        if (c == '\n') {
            int end = Characters.skipBackwards(' ', content, content.length() - 1, 0) + 1;
            trailingSpaces = content.length() - end;
            content = content.substring(0, end);
        } else if (c == Scanner.END) {
            int end = Characters.skipSpaceTabBackwards(content, content.length() - 1, 0) + 1;
            content = content.substring(0, end);
        }

        Text text = new Text(content);
        if (includeSourceSpans) {
            text.setSourceSpans(scanner.getSource(Scanner.positionFromLong(start), Scanner.positionFromLong(endPos)).getSourceSpans());
        }
        return text;
    }

    private final Map<Character, Delimiter> openersBottom = new HashMap<>();

    // Reusable fields for scanDelimiters result (avoids DelimiterData allocation)
    private List<Text> lastDelimChars;
    private boolean lastDelimCanOpen, lastDelimCanClose;

    // Reusable fields for parseInlineDestinationTitle (avoids DestinationTitle allocation)
    private String lastDestination, lastTitle;

    private boolean scanDelimiters(DelimiterProcessor delimiterProcessor, char delimiterChar) {
        int before = scanner.peekPreviousCodePoint();
        long start = scanner.positionAsLong();

        int delimiterCount = scanner.matchMultiple(delimiterChar);
        if (delimiterCount < delimiterProcessor.getMinLength()) {
            scanner.setPositionFromLong(start);
            return false;
        }

        String delimStr = String.valueOf(delimiterChar);
        var delimiters = new ArrayList<Text>(delimiterCount);
        if (includeSourceSpans) {
            // Rewind and re-scan character by character to capture source spans
            scanner.setPositionFromLong(start);
            Position positionBefore = scanner.position();
            for (int i = 0; i < delimiterCount; i++) {
                scanner.next();
                delimiters.add(text(positionBefore, scanner.position()));
                positionBefore = scanner.position();
            }
        } else {
            // Fast path: no rewind needed, scanner is already past the delimiters
            for (int i = 0; i < delimiterCount; i++) {
                delimiters.add(new Text(delimStr));
            }
        }

        int after = scanner.peekCodePoint();

        boolean beforeIsPunctuation = before == Scanner.END || Characters.isPunctuationCodePoint(before);
        boolean beforeIsWhitespace = before == Scanner.END || Characters.isWhitespaceCodePoint(before);
        boolean afterIsPunctuation = after == Scanner.END || Characters.isPunctuationCodePoint(after);
        boolean afterIsWhitespace = after == Scanner.END || Characters.isWhitespaceCodePoint(after);

        boolean leftFlanking = !afterIsWhitespace &&
                (!afterIsPunctuation || beforeIsWhitespace || beforeIsPunctuation);
        boolean rightFlanking = !beforeIsWhitespace &&
                (!beforeIsPunctuation || afterIsWhitespace || afterIsPunctuation);

        if (delimiterChar == '_') {
            lastDelimCanOpen = leftFlanking && (!rightFlanking || beforeIsPunctuation);
            lastDelimCanClose = rightFlanking && (!leftFlanking || afterIsPunctuation);
        } else {
            lastDelimCanOpen = leftFlanking && delimiterChar == delimiterProcessor.getOpeningCharacter();
            lastDelimCanClose = rightFlanking && delimiterChar == delimiterProcessor.getClosingCharacter();
        }
        lastDelimChars = delimiters;
        return true;
    }

    private void processDelimiters(Delimiter stackBottom) {
        openersBottom.clear();

        // find first closer above stackBottom:
        Delimiter closer = lastDelimiter;
        while (closer != null && closer.previous != stackBottom) {
            closer = closer.previous;
        }
        // move forward, looking for closers, and handling each
        while (closer != null) {
            char delimiterChar = closer.delimiterChar;

            // Fast array path for ASCII (all delimiter chars we care about are ASCII)
            DelimiterProcessor delimiterProcessor = delimiterChar < 128
                    ? delimiterProcessorsByChar[delimiterChar]
                    : delimiterProcessors.get(delimiterChar);
            if (!closer.canClose() || delimiterProcessor == null) {
                closer = closer.next;
                continue;
            }

            char openingDelimiterChar = delimiterProcessor.getOpeningCharacter();

            // Found delimiter closer. Now look back for first matching opener.
            int usedDelims = 0;
            boolean openerFound = false;
            boolean potentialOpenerFound = false;
            Delimiter opener = closer.previous;
            // Cache the openersBottom lookup — doesn't change during inner loop
            Delimiter openersBottomForChar = openersBottom.get(delimiterChar);
            while (opener != null && opener != stackBottom && opener != openersBottomForChar) {
                if (opener.canOpen() && opener.delimiterChar == openingDelimiterChar) {
                    potentialOpenerFound = true;
                    usedDelims = delimiterProcessor.process(opener, closer);
                    if (usedDelims > 0) {
                        openerFound = true;
                        break;
                    }
                }
                opener = opener.previous;
            }

            if (!openerFound) {
                if (!potentialOpenerFound) {
                    // Set lower bound for future searches for openers.
                    // Only do this when we didn't even have a potential
                    // opener (one that matches the character and can open).
                    // If an opener was rejected because of the number of
                    // delimiters (e.g. because of the "multiple of 3" rule),
                    // we want to consider it next time because the number
                    // of delimiters can change as we continue processing.
                    openersBottom.put(delimiterChar, closer.previous);
                    if (!closer.canOpen()) {
                        // We can remove a closer that can't be an opener,
                        // once we've seen there's no matching opener:
                        removeDelimiterKeepNode(closer);
                    }
                }
                closer = closer.next;
                continue;
            }

            // Remove number of used delimiters nodes.
            for (int i = 0; i < usedDelims; i++) {
                Text delimiter = opener.characters.remove(opener.characters.size() - 1);
                delimiter.unlink();
            }
            for (int i = 0; i < usedDelims; i++) {
                Text delimiter = closer.characters.remove(0);
                delimiter.unlink();
            }

            removeDelimitersBetween(opener, closer);

            // No delimiter characters left to process, so we can remove delimiter and the now empty node.
            if (opener.length() == 0) {
                removeDelimiterAndNodes(opener);
            }

            if (closer.length() == 0) {
                Delimiter next = closer.next;
                removeDelimiterAndNodes(closer);
                closer = next;
            }
        }

        // remove all delimiters
        while (lastDelimiter != null && lastDelimiter != stackBottom) {
            removeDelimiterKeepNode(lastDelimiter);
        }
    }

    private void removeDelimitersBetween(Delimiter opener, Delimiter closer) {
        Delimiter delimiter = closer.previous;
        while (delimiter != null && delimiter != opener) {
            Delimiter previousDelimiter = delimiter.previous;
            removeDelimiterKeepNode(delimiter);
            delimiter = previousDelimiter;
        }
    }

    /**
     * Remove the delimiter and the corresponding text node. For used delimiters, e.g. `*` in `*foo*`.
     */
    private void removeDelimiterAndNodes(Delimiter delim) {
        removeDelimiter(delim);
    }

    /**
     * Remove the delimiter but keep the corresponding node as text. For unused delimiters such as `_` in `foo_bar`.
     */
    private void removeDelimiterKeepNode(Delimiter delim) {
        removeDelimiter(delim);
    }

    private void removeDelimiter(Delimiter delim) {
        if (delim.previous != null) {
            delim.previous.next = delim.next;
        }
        if (delim.next == null) {
            // top of stack
            lastDelimiter = delim.previous;
        } else {
            delim.next.previous = delim.previous;
        }
    }

    private void mergeChildTextNodes(Node node) {
        // No children, no need for merging
        if (node.getFirstChild() == null) {
            return;
        }

        mergeTextNodesInclusive(node.getFirstChild(), node.getLastChild());
    }

    private void mergeTextNodesInclusive(Node fromNode, Node toNode) {
        Text first = null;
        Text last = null;
        int length = 0;

        Node node = fromNode;
        while (node != null) {
            if (node instanceof Text) {
                Text text = (Text) node;
                if (first == null) {
                    first = text;
                }
                length += text.getLiteral().length();
                last = text;
            } else {
                mergeIfNeeded(first, last, length);
                first = null;
                last = null;
                length = 0;

                mergeChildTextNodes(node);
            }
            if (node == toNode) {
                break;
            }
            node = node.getNext();
        }

        mergeIfNeeded(first, last, length);
    }

    private void mergeIfNeeded(Text first, Text last, int textLength) {
        if (first != null && last != null && first != last) {
            StringBuilder sb = new StringBuilder(textLength);
            sb.append(first.getLiteral());
            SourceSpans sourceSpans = null;
            if (includeSourceSpans) {
                sourceSpans = new SourceSpans();
                sourceSpans.addAll(first.getSourceSpans());
            }
            Node node = first.getNext();
            Node stop = last.getNext();
            while (node != stop) {
                sb.append(((Text) node).getLiteral());
                if (sourceSpans != null) {
                    sourceSpans.addAll(node.getSourceSpans());
                }

                Node unlink = node;
                node = node.getNext();
                unlink.unlink();
            }
            String literal = sb.toString();
            first.setLiteral(literal);
            if (sourceSpans != null) {
                first.setSourceSpans(sourceSpans.getSourceSpans());
            }
        }
    }

    private static class LinkInfoImpl implements LinkInfo {

        private final Text marker;
        private final Text openingBracket;
        private final String text;
        private final String label;
        private final String destination;
        private final String title;
        private final Position afterTextBracket;

        private LinkInfoImpl(Text marker, Text openingBracket, String text, String label,
                             String destination, String title, Position afterTextBracket) {
            this.marker = marker;
            this.openingBracket = openingBracket;
            this.text = text;
            this.label = label;
            this.destination = destination;
            this.title = title;
            this.afterTextBracket = afterTextBracket;
        }

        @Override
        public Text marker() {
            return marker;
        }

        @Override
        public Text openingBracket() {
            return openingBracket;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String destination() {
            return destination;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public Position afterTextBracket() {
            return afterTextBracket;
        }
    }
}
