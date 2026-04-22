package org.commonmark.text;

import java.util.Set;

/**
 * Char matcher that can match ASCII characters efficiently.
 */
public class AsciiMatcher implements CharMatcher {
    private final boolean[] set;

    private AsciiMatcher(Builder builder) {
        // Copy the builder's array so the built matcher is immutable.
        this.set = builder.set.clone();
    }

    @Override
    public boolean matches(char c) {
        return c < 128 && set[c];
    }

    public Builder newBuilder() {
        return new Builder(set.clone());
    }

    public static Builder builder() {
        return new Builder(new boolean[128]);
    }

    public static Builder builder(AsciiMatcher matcher) {
        return new Builder(matcher.set.clone());
    }

    public static class Builder {
        private final boolean[] set;

        private Builder(boolean[] set) {
            this.set = set;
        }

        public Builder c(char c) {
            if (c > 127) {
                throw new IllegalArgumentException("Can only match ASCII characters");
            }
            set[c] = true;
            return this;
        }

        public Builder anyOf(String s) {
            for (int i = 0; i < s.length(); i++) {
                c(s.charAt(i));
            }
            return this;
        }

        public Builder anyOf(Set<Character> characters) {
            for (Character c : characters) {
                c(c);
            }
            return this;
        }

        public Builder range(char from, char toInclusive) {
            for (char c = from; c <= toInclusive; c++) {
                c(c);
            }
            return this;
        }

        public AsciiMatcher build() {
            return new AsciiMatcher(this);
        }
    }
}
