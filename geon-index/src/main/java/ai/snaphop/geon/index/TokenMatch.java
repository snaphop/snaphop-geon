package ai.snaphop.geon.index;

/// How a token matched a doc.
record TokenMatch(Token token, double quality, int field) {
}
