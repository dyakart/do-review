package dev.doreview.ide;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewedRange;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cache metadata may permit fresh matching only when no known baseline-text copy exists. */
public final class CheckpointTransferTest extends BasePlatformTestCase {
    private static final String BASELINE = "head\nchecked();\ntail\n";
    private static final String CHANGED = "head\naltered();\ntail\n";
    private ReviewHistoryStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = new ReviewHistoryStore(Path.of(getProject().getBasePath(), "checkpoint-backups"));
    }

    public void testV1NoVersion() {
        var sample = sample(CHANGED);
        var checkpoint = ProjectionCheckpointCodec.encode(sample.history(), CHANGED, sample.hints());
        checkpoint.payload.version = 1;
        checkpoint.payload.refs.getFirst().allowRematch = false;
        // The old checksum predates the new field, including its false/default serialization.
        var oldCanonicalPayload = XmlSerializer.serialize(checkpoint.payload);
        omitOption(oldCanonicalPayload, "allowRematch");
        checkpoint.checksum = sha256(JDOMUtil.writeElement(oldCanonicalPayload));
        var xml = XmlSerializer.serialize(checkpoint);
        omitOption(xml, "version");
        omitOption(xml, "allowRematch");
        assertFalse("Old XML omits the payload's default version field",
                JDOMUtil.writeElement(xml).contains("name=\"version\""));
        assertFalse("Old XML does not contain the later rematch-policy field",
                JDOMUtil.writeElement(xml).contains("allowRematch"));
        var loaded = XmlSerializer.deserialize(xml, ReviewHistoryStore.CheckpointState.class);
        assertEquals(1, loaded.payload.version);
        assertEquals(checkpoint.checksum, ProjectionCheckpointCodec.checksum(loaded.payload));
        assertEquals(sample.hints(), ProjectionCheckpointCodec.decode(sample.history(), CHANGED, loaded));

        var state = store.getState();
        var file = state.projects.values().iterator().next().files.values().iterator().next();
        file.checkpoints.put(loaded.payload.textHash, loaded);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), ReviewHistoryStore.StoreState.class));

        var transferred = store.checkpoint(getProject(), "a.txt", BASELINE + "offline\n");
        assertEquals("V1 cannot prove a same-length live edit without its original checkpoint text",
                Set.of(sample.ref()), transferred.authoritativeRefs());
        assertEmpty(transferred.livePositions().keySet());
        loaded.payload.refs.getFirst().allowRematch = true;
        assertRejected(sample.history(), CHANGED, loaded);
    }

    public void testV2SameLength() {
        assertEquals(BASELINE.length(), CHANGED.length());
        var sample = sample(CHANGED);
        store.checkpoint(getProject(), "a.txt", CHANGED, sample.hints());
        var state = store.getState();
        var checkpoint = state.projects.values().iterator().next().files.values().iterator().next()
                .checkpoints.values().iterator().next();
        assertEquals(2, checkpoint.payload.version);
        assertTrue("A changed live row without an existing baseline-text copy may be rematched",
                checkpoint.payload.refs.getFirst().allowRematch);
        store.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), ReviewHistoryStore.StoreState.class));

        var transferred = store.checkpoint(getProject(), "a.txt", BASELINE + "offline\n");
        assertEmpty("A stored positive changed-row locator must not become a lost-origin restriction",
                transferred.authoritativeRefs());
        assertEmpty(transferred.livePositions().keySet());
    }

    public void testFlagChecksum() {
        var sample = sample(CHANGED);
        var checkpoint = ProjectionCheckpointCodec.encode(sample.history(), CHANGED, sample.hints());
        assertTrue(checkpoint.payload.refs.getFirst().allowRematch);
        checkpoint.payload.refs.getFirst().allowRematch = false;

        try {
            ProjectionCheckpointCodec.decode(sample.history(), CHANGED, checkpoint);
            fail("Changing the rematch policy must invalidate the checkpoint checksum");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }

    public void testExactFlag() {
        for (String current : List.of(BASELINE, "head\nchecked();\nchecked();\ntail\n",
                "head\naltered();\nchecked();\ntail\n")) {
            var sample = sample(current);
            var checkpoint = ProjectionCheckpointCodec.encode(sample.history(), current, sample.hints());
            assertFalse("An unchanged row or a known baseline-text copy must retain its restriction",
                    checkpoint.payload.refs.getFirst().allowRematch);
            checkpoint.payload.refs.getFirst().allowRematch = true;
            checkpoint.checksum = ProjectionCheckpointCodec.checksum(checkpoint.payload);

            assertRejected(sample.history(), current, checkpoint);
        }
    }

    public void testFlagNeedsLive() {
        var sample = sample(CHANGED);
        var checkpoint = ProjectionCheckpointCodec.encode(sample.history(), CHANGED, sample.hints());
        assertTrue(checkpoint.payload.refs.getFirst().allowRematch);
        checkpoint.payload.refs.getFirst().live = null;
        checkpoint.checksum = ProjectionCheckpointCodec.checksum(checkpoint.payload);

        assertRejected(sample.history(), CHANGED, checkpoint);
    }

    public void testCopiesFlag() {
        var sample = sample(CHANGED);
        var checkpoint = ProjectionCheckpointCodec.encode(sample.history(), CHANGED, sample.hints());
        var detached = ReviewHistoryStateCopies.checkpoint(checkpoint);
        assertNotSame(checkpoint.payload, detached.payload);
        assertNotSame(checkpoint.payload.refs.getFirst(), detached.payload.refs.getFirst());
        assertNotSame(checkpoint.payload.refs.getFirst().live, detached.payload.refs.getFirst().live);
        assertTrue(detached.payload.refs.getFirst().allowRematch);
        assertEquals(checkpoint.checksum, ProjectionCheckpointCodec.checksum(detached.payload));

        detached.payload.refs.getFirst().allowRematch = false;
        detached.payload.refs.getFirst().live.start++;
        assertTrue("Mutating an exported bean must not change the live rematch policy",
                checkpoint.payload.refs.getFirst().allowRematch);
        assertEquals(BASELINE.indexOf("checked"), checkpoint.payload.refs.getFirst().live.start);
    }

    private Sample sample(String current) {
        int start = BASELINE.indexOf("checked"), end = BASELINE.indexOf("tail");
        var proof = store.mark(getProject(), "a.txt", BASELINE, start, end);
        var ref = new EvidenceRef(proof.id(), start, end);
        var hints = new ProjectionHints(Map.of(ref, new ReviewedRange(start, current.indexOf('\n', start) + 1)),
                Set.of(ref), Set.of(), Map.of(), Map.of(ref, BASELINE.substring(start, end)), Map.of());
        return new Sample(store.history(getProject(), "a.txt"), ref, hints);
    }

    private static void assertRejected(ReviewHistory history, String text, ReviewHistoryStore.CheckpointState checkpoint) {
        try {
            ProjectionCheckpointCodec.decode(history, text, checkpoint);
            fail("An invalid rematch policy must not be accepted even with a recomputed checksum");
        } catch (IllegalArgumentException expected) {
            // Structural validation must reject a policy the exact checkpoint text disproves.
        }
    }

    private static void omitOption(org.jdom.Element element, String name) {
        for (var child : List.copyOf(element.getChildren())) {
            if ("option".equals(child.getName()) && name.equals(child.getAttributeValue("name"))) child.detach();
            else omitOption(child, name);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Sample(ReviewHistory history, EvidenceRef ref, ProjectionHints hints) { }
}
