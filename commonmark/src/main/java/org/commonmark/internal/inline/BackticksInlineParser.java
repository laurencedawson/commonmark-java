package org.commonmark.internal.inline;

import org.commonmark.node.Code;
import org.commonmark.node.Text;
import org.commonmark.parser.beta.*;
import org.commonmark.text.Characters;

import java.util.Set;

/**
 * Attempt to parse backticks, returning either a backtick code span or a literal sequence of backticks.
 */
public class BackticksInlineParser implements InlineContentParser {

    // Track which backtick counts have been scanned and found to have no closing match.
    // Bit N set = we scanned for N backticks and found no match, so don't try again.
    // Supports up to 63 backticks (long has 64 bits, bit 0 unused since 0 backticks is meaningless).
    private long scannedCounts;

    @Override
    public ParsedInline tryParse(InlineParserState inlineParserState) {
        Scanner scanner = inlineParserState.scanner();
        Position start = scanner.position();
        int openingTicks = scanner.matchMultiple('`');
        Position afterOpening = scanner.position();

        // If we already know there's no closing sequence of this length, fail fast
        if (openingTicks < 64 && (scannedCounts & (1L << openingTicks)) != 0) {
            Text text = new Text(scanner.getContentBetween(start, afterOpening));
            return ParsedInline.of(text, afterOpening);
        }

        while (scanner.find('`') > 0) {
            Position beforeClosing = scanner.position();
            int count = scanner.matchMultiple('`');
            if (count == openingTicks) {
                Code node = new Code();

                String content = scanner.getContentBetween(afterOpening, beforeClosing);
                content = content.replace('\n', ' ');

                if (content.length() >= 3 &&
                        content.charAt(0) == ' ' &&
                        content.charAt(content.length() - 1) == ' ' &&
                        Characters.hasNonSpace(content)) {
                    content = content.substring(1, content.length() - 1);
                }

                node.setLiteral(content);
                return ParsedInline.of(node, scanner.position());
            }
        }

        // No matching closing sequence found. Remember this so we don't rescan.
        if (openingTicks < 64) {
            scannedCounts |= (1L << openingTicks);
        }

        Text text = new Text(scanner.getContentBetween(start, afterOpening));
        return ParsedInline.of(text, afterOpening);
    }

    public static class Factory implements InlineContentParserFactory {
        @Override
        public Set<Character> getTriggerCharacters() {
            return Set.of('`');
        }

        @Override
        public InlineContentParser create() {
            return new BackticksInlineParser();
        }
    }
}
