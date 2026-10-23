package dev.doreview.ide;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.Key;

/** Stable presentation keys shared by rendering, color schemes and UI tests. */
final class ReviewMarkKeys {
    static final TextAttributesKey REVIEWED = TextAttributesKey.createTextAttributesKey("REVIEW_MARKS_REVIEWED");
    static final TextAttributesKey NEEDS_REVIEW = TextAttributesKey.createTextAttributesKey("REVIEW_MARKS_NEEDS_REVIEW");
    static final Key<Boolean> REVIEWED_HIGHLIGHTER = Key.create("ReviewMarks.reviewedHighlighter");
    static final Key<Boolean> NEEDS_REVIEW_HIGHLIGHTER = Key.create("ReviewMarks.needsReviewHighlighter");
    static final Key<Boolean> CHANGED_HIGHLIGHTER = Key.create("ReviewMarks.changedHighlighter");

    private ReviewMarkKeys() { }
}
