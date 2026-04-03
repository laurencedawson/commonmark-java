package org.commonmark.internal;

class BlockContent {

    private String first;
    private StringBuilder sb;

    public BlockContent() {
    }

    public BlockContent(String content) {
        first = content;
    }

    public void add(CharSequence line) {
        if (first == null && sb == null) {
            first = line.toString();
        } else {
            if (sb == null) {
                sb = new StringBuilder(first);
                first = null;
            }
            sb.append('\n');
            sb.append(line);
        }
    }

    public String getString() {
        if (sb != null) return sb.toString();
        if (first != null) return first;
        return "";
    }

}
