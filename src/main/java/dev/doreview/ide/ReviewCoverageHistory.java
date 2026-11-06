package dev.doreview.ide;

import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewHistory;

import java.util.concurrent.CompletableFuture;

/** Task-local immutable history queries; inspecting coverage never publishes review work. */
interface ReviewCoverageHistory {
    record Input(ReviewHistory history, ProjectionHints hints) { }

    CompletableFuture<Input> read(String projectRelativePath, String text, boolean git);
    CompletableFuture<Boolean> isCurrent();
}
