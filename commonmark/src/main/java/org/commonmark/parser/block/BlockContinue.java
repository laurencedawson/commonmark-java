package org.commonmark.parser.block;

import org.commonmark.internal.BlockContinueImpl;

/**
 * Result object for continuing parsing of a block, see static methods for constructors.
 */
public class BlockContinue {

    private static final BlockContinueImpl FINISHED = new BlockContinueImpl(-1, -1, true);
    private static final BlockContinueImpl AT_INDEX_0 = new BlockContinueImpl(0, -1, false);

    protected BlockContinue() {
    }

    public static BlockContinue none() {
        return null;
    }

    public static BlockContinue atIndex(int newIndex) {
        if (newIndex == 0) {
            return AT_INDEX_0;
        }
        return new BlockContinueImpl(newIndex, -1, false);
    }

    public static BlockContinue atColumn(int newColumn) {
        return new BlockContinueImpl(-1, newColumn, false);
    }

    public static BlockContinue finished() {
        return FINISHED;
    }

}
