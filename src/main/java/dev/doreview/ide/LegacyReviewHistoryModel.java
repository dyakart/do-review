package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.StateStorageChooserEx;
import com.intellij.openapi.components.StateStorageOperation;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Key;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.Transient;
import com.intellij.util.messages.Topic;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewEvidence;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewHistoryEngine;
import dev.doreview.core.ReviewedRange;
import dev.doreview.core.Revocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Detached legacy journal model, also used for validated per-file working state. No XML writer is registered. */
class LegacyReviewHistoryModel implements StateStorageChooserEx {
    private static final Key<String> PROJECT_KEY = Key.create("ReviewMarks.canonicalHistoryProject");
    public static final Topic<HistoryListener> TOPIC = Topic.create("DoReview history reloaded", HistoryListener.class);
    public interface HistoryListener { void historyChanged(); }
    private final Object lock = new Object();
    private final Path backupRoot;
    private StoreState state = emptyState();
    private boolean unsupportedSchema;
    private long modificationCount;
    private final List<String> loadWarnings = new ArrayList<>();

    LegacyReviewHistoryModel(Path backupRoot) { this.backupRoot = backupRoot; }

    private static StoreState emptyState() {
        var state = new StoreState(); state.schemaVersion = 2; return state;
    }

    public ReviewHistory history(Project project, String relativePath) {
        synchronized (lock) {
            if (unsupportedSchema) return ReviewHistory.empty();
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            ReviewHistory history = file == null ? null : safeRead(entry, file);
            return history == null ? ReviewHistory.empty() : history;
        }
    }

    /** Exact content restores positions; unseen content retains previous restrictions without coordinates. */
    public ProjectionHints checkpoint(Project project, String relativePath, String text) {
        synchronized (lock) {
            if (unsupportedSchema) return ProjectionHints.empty();
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            ReviewHistory history = file == null ? null : safeRead(entry, file);
            if (history == null) return ProjectionHints.empty();
            try {
                if (file.checkpoints == null) throw new IllegalArgumentException("Missing checkpoint collection");
                if (file.runtimePending != null && file.runtimePending.text().equals(text)) return file.runtimePending.hints();
                String hash = FileSnapshot.create(text).id();
                RuntimeCheckpoint settled = file.runtimeCheckpoints.get(hash);
                if (settled != null) return settled.hints();
                if (file.pendingCheckpoint != null && (file.pendingCheckpoint.payload == null
                        || hash.equals(file.pendingCheckpoint.payload.textHash)) && file.runtimePending == null) {
                    return validated(file, file.pendingCheckpoint).forText(text, hash);
                }
                if (file.checkpoints.containsKey(hash)) return validated(file, file.checkpoints.get(hash)).forText(text, hash);
                // A changed live original without an existing exact-text copy may rematch.
                // Keep real lost origins and locators that distinguish known duplicate copies.
                var authority = new HashSet<EvidenceRef>();
                for (var checkpoint : file.checkpoints.entrySet()) {
                    if (file.runtimeCheckpoints.containsKey(checkpoint.getKey())) continue;
                    var value = validated(file, checkpoint.getValue());
                    if (!value.textHash().equals(checkpoint.getKey())) throw new IllegalArgumentException("Checkpoint key mismatch");
                    authority.addAll(value.restrictions());
                }
                file.runtimeCheckpoints.values().forEach(value -> addRestrictions(authority, value));
                if (file.runtimePending != null) addRestrictions(authority, file.runtimePending);
                else if (file.pendingCheckpoint != null) authority.addAll(validated(file, file.pendingCheckpoint).restrictions());
                return new ProjectionHints(Map.of(), authority);
            } catch (RuntimeException invalid) {
                readWarning(entry, "Ignored damaged review provenance checkpoint for " + file.path + ": " + invalid.getMessage());
                return ProjectionCheckpointCodec.failClosed(evidenceIndex(file));
            }
        }
    }

    private static void addRestrictions(Set<EvidenceRef> authority, RuntimeCheckpoint checkpoint) {
        var hints = checkpoint.hints();
        var rematchable = ProjectionCheckpointCodec.rematchable(hints, checkpoint.text());
        for (var ref : hints.authoritativeRefs()) {
            if (!rematchable.contains(ref)) authority.add(ref);
        }
    }

    /** Persists compact identity hints without changing immutable review history or its revision. */
    public void checkpoint(Project project, String relativePath, String text, ProjectionHints hints) {
        String hash = FileSnapshot.create(text).id();
        ProjectionHints compact = hints.compactForCheckpoint(text);
        synchronized (lock) {
            if (unsupportedSchema) return;
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            ReviewHistory history = file == null ? null : safeRead(entry, file);
            if (history == null) return;
            file.runtimeCheckpoints.put(hash, new RuntimeCheckpoint(text, compact));
            modificationCount++;
            if (file.runtimePending != null && file.runtimePending.text().equals(text)) file.runtimePending = null;
            if (file.pendingCheckpoint != null && file.pendingCheckpoint.payload != null
                    && hash.equals(file.pendingCheckpoint.payload.textHash)) clearPending(file);
        }
    }

    /** Pure preparation for a background worker: no project lookup, filesystem access or store lock. */
    public PreparedCheckpoint prepareCheckpoint(ReviewHistory history, String text, ProjectionHints hints) {
        ProjectionHints compact = hints.compactForCheckpoint(text);
        var index = ProjectionCheckpointCodec.index(history, compact);
        var encoded = ProjectionCheckpointCodec.encode(index, text, compact);
        return new PreparedCheckpoint(history, text, compact, encoded);
    }

    /** Commits only the immutable history version used by the worker; stale work is rejected. */
    public boolean commitCheckpoint(Project project, String relativePath, PreparedCheckpoint prepared) {
        synchronized (lock) {
            if (unsupportedSchema) return false;
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            if (file == null || file.cachedHistory != prepared.expectedHistory) return false;
            String hash = prepared.encoded.payload.textHash;
            if (file.checkpoints == null) file.checkpoints = new TreeMap<>();
            replaceCheckpoint(file, hash, prepared.encoded);
            modificationCount++;
            file.runtimeCheckpoints.remove(hash);
            file.validatedCheckpoints.put(prepared.encoded,
                    ProjectionCheckpointCodec.cached(prepared.encoded, prepared.hints));
            if (file.runtimePending != null && file.runtimePending.text().equals(prepared.text)) file.runtimePending = null;
            if (file.pendingCheckpoint != null && file.pendingCheckpoint.payload != null
                    && hash.equals(file.pendingCheckpoint.payload.textHash)) clearPending(file);
            return true;
        }
    }

    /** One replaceable pre-worker checkpoint bounds storage while document edits are coalescing. */
    public void checkpointPending(Project project, String relativePath, String text, ProjectionHints hints) {
        synchronized (lock) {
            if (unsupportedSchema) return;
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            ReviewHistory history = file == null ? null : safeRead(entry, file);
            if (history == null) return;
            clearPending(file);
            file.runtimePending = new RuntimeCheckpoint(text, hints);
            modificationCount++;
        }
    }

    private static void clearPending(FileState file) {
        forgetValidation(file, file.pendingCheckpoint);
        file.pendingCheckpoint = null;
    }

    private static void replaceCheckpoint(FileState file, String hash, CheckpointState checkpoint) {
        CheckpointState previous = file.checkpoints.put(hash, checkpoint);
        if (previous != checkpoint) forgetValidation(file, previous);
    }

    private static void forgetValidation(FileState file, CheckpointState checkpoint) {
        if (checkpoint == null) return;
        file.validatedCheckpoints.remove(checkpoint);
        file.invalidCheckpoints.remove(checkpoint);
    }

    private static ProjectionCheckpointCodec.EvidenceIndex evidenceIndex(FileState file) {
        if (file.evidenceIndex == null) file.evidenceIndex = ProjectionCheckpointCodec.index(readHistory(file));
        return file.evidenceIndex;
    }

    private static ProjectionCheckpointCodec.ValidatedCheckpoint validated(FileState file, CheckpointState checkpoint) {
        String invalid = file.invalidCheckpoints.get(checkpoint);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        var cached = file.validatedCheckpoints.get(checkpoint);
        if (cached == null) {
            try {
                cached = ProjectionCheckpointCodec.validate(evidenceIndex(file), checkpoint);
                file.validatedCheckpoints.put(checkpoint, cached);
            } catch (RuntimeException failure) {
                file.invalidCheckpoints.put(checkpoint, failure.getMessage() == null ? "Invalid checkpoint" : failure.getMessage());
                throw failure;
            }
        }
        return cached;
    }

    public @Nullable ReviewEvidence mark(Project project, String relativePath, String text, int start, int end) {
        synchronized (lock) {
            requireWritableSchema();
            var range = new ReviewedRange(start, end);
            String normalized = FileSnapshot.normalize(text);
            if (end > normalized.length()) throw new IllegalArgumentException("Review exceeds current snapshot");
            int first = normalized.lastIndexOf('\n', start - 1) + 1;
            int newline = normalized.indexOf('\n', end - 1);
            int last = newline < 0 ? normalized.length() : newline + 1;
            // A blank-only decision must not create a file record or switch an existing alias.
            if (normalized.substring(first, last).isBlank()) return null;
            ProjectState entry = projectState(project);
            FileState file = writableFile(project, entry, relativePath);
            ReviewHistory before = readHistory(file);
            ReviewHistory next = ReviewHistoryEngine.review(before, text,
                    List.of(range), System.currentTimeMillis());
            if (next == before) return null;
            writeHistory(file, next);
            // Once new review decisions exist, later checkout of stale legacy XML cannot replay them.
            entry.legacyImported = true;
            modificationCount++;
            return next.evidence().getLast();
        }
    }

    public void revoke(Project project, String relativePath, List<EvidenceRef> references) {
        synchronized (lock) {
            requireWritableSchema();
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            ReviewHistory history = file == null ? null : safeRead(entry, file);
            if (history != null) {
                var next = ReviewHistoryEngine.revoke(history, references);
                if (next != history) { writeHistory(file, next); modificationCount++; }
            }
        }
    }

    /** Routes already selected proof references to their owners without filesystem alias scans. */
    public void revokeByEvidence(Project project, List<EvidenceRef> references) {
        if (references.isEmpty()) return;
        synchronized (lock) {
            requireWritableSchema();
            ProjectState entry = state.projects.get(projectKey(project));
            if (entry == null) return;
            var owners = new HashMap<String, FileState>();
            var ambiguous = new HashSet<String>();
            for (var file : entry.files.values()) {
                if (file == null) continue;
                // Ownership routing needs IDs only; do not decode unrelated source snapshots.
                var ids = file.cachedHistory != null ? file.cachedHistory.evidence().stream().map(ReviewEvidence::id).toList()
                        : file.evidence.stream().filter(java.util.Objects::nonNull).map(proof -> proof.id).toList();
                for (String id : ids) {
                    var previous = owners.putIfAbsent(id, file);
                    if (previous != null && previous != file) ambiguous.add(id);
                }
            }
            var grouped = new IdentityHashMap<FileState, List<EvidenceRef>>();
            for (var ref : references) {
                if (ambiguous.contains(ref.evidenceId())) {
                    readWarning(entry, "Skipped review revocation with ambiguous evidence ownership"); continue;
                }
                var owner = owners.get(ref.evidenceId());
                if (owner != null) grouped.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(ref);
            }
            for (var group : grouped.entrySet()) {
                ReviewHistory history = safeRead(entry, group.getKey());
                if (history == null) continue;
                var next = ReviewHistoryEngine.revoke(history, group.getValue());
                if (next != history) { writeHistory(group.getKey(), next); modificationCount++; }
            }
        }
    }

    /** Revokes every recorded variant, including evidence that is currently changed or missing. */
    public void clearFile(Project project, String relativePath) {
        synchronized (lock) {
            requireWritableSchema();
            ProjectState entry = state.projects.get(projectKey(project));
            FileState file = entry == null ? null : findFile(project, entry, relativePath);
            if (file == null) return;
            ReviewHistory history = safeRead(entry, file);
            if (history == null) return;
            List<EvidenceRef> references = new ArrayList<>();
            for (ReviewEvidence evidence : history.evidence()) {
                for (ReviewedRange range : evidence.ranges()) {
                    references.add(new EvidenceRef(evidence.id(), range.start(), range.end()));
                }
            }
            var next = ReviewHistoryEngine.revoke(history, references);
            if (next != history) { writeHistory(file, next); modificationCount++; }
        }
    }

    /** Known paths for file histories, using an existing unambiguous alias when a branch restored it. */
    public List<String> paths(Project project) {
        synchronized (lock) {
            if (unsupportedSchema) return List.of();
            ProjectState entry = state.projects.get(projectKey(project));
            if (entry == null) return List.of();
            TreeSet<String> result = new TreeSet<>();
            entry.files.forEach((id, file) -> {
                if (file == null || !validPath(file.path)) {
                    readWarning(entry, "Skipped damaged review history path for record " + id);
                    return;
                }
                List<String> existing = existingAliases(project, entry, id);
                result.add(existing.contains(file.path) || existing.size() != 1 ? file.path : existing.getFirst());
            });
            return List.copyOf(result);
        }
    }

    /** Explicit/observed rename only. Old aliases remain usable after checking out an earlier branch. */
    public boolean movePath(Project project, String oldPath, String newPath) {
        oldPath = relativePath(oldPath);
        newPath = relativePath(newPath);
        synchronized (lock) {
            requireWritableSchema();
            ProjectState entry = state.projects.get(projectKey(project));
            if (entry == null) return false;
            Map<String, String> destinations = new LinkedHashMap<>();
            Map<String, String> primaryPaths = new LinkedHashMap<>();
            for (var alias : entry.aliases.entrySet()) {
                if (!matches(alias.getKey(), oldPath)) continue;
                FileState source = entry.files.get(alias.getValue());
                if (source == null || (!alias.getKey().equals(source.path)
                        && Files.isRegularFile(Path.of(project.getBasePath()).resolve(source.path)))) continue;
                String destination = newPath + alias.getKey().substring(oldPath.length());
                String collision = entry.aliases.get(destination);
                if (collision != null && !collision.equals(alias.getValue())) {
                    if (warning(entry, "Review history was not moved: destination already has independent history: " + destination)) modificationCount++;
                    return false;
                }
                destinations.put(destination, alias.getValue());
                if (alias.getKey().equals(source.path) || !primaryPaths.containsKey(alias.getValue())) {
                    primaryPaths.put(alias.getValue(), destination);
                }
            }
            destinations.forEach(entry.aliases::put);
            primaryPaths.forEach((id, path) -> entry.files.get(id).path = path);
            if (!destinations.isEmpty()) modificationCount++;
            return !destinations.isEmpty();
        }
    }

    public List<String> diagnostics(Project project) {
        synchronized (lock) {
            ProjectState entry = state.projects.get(projectKey(project));
            List<String> warnings = new ArrayList<>(loadWarnings);
            if (entry != null) { warnings.addAll(entry.warnings); warnings.addAll(entry.readWarnings); }
            return List.copyOf(warnings);
        }
    }

    public MigrationResult importLegacyFromProject(Project project) {
        synchronized (lock) {
            if (unsupportedSchema) return new MigrationResult(0, 0, List.copyOf(loadWarnings));
            if (projectState(project).legacyImported) return MigrationResult.empty();
        }
        Path source = legacyPath(project);
        if (Files.notExists(source)) {
            synchronized (lock) {
                ProjectState entry = projectState(project);
                if (!entry.legacyImported) { entry.legacyImported = true; modificationCount++; }
            }
            return MigrationResult.empty();
        }
        if (!Files.isRegularFile(source)) return migrationFailure(project, "Legacy review source is not a readable file: " + source);
        try {
            byte[] raw = Files.readAllBytes(source);
            backupLegacy(project, raw);
            var root = JDOMUtil.load(new ByteArrayInputStream(raw));
            var component = root.getName().equals("component") ? root : root.getChildren("component").stream()
                    .filter(child -> "ReviewMarks".equals(child.getAttributeValue("name"))).findFirst().orElse(null);
            if (component == null || !"ReviewMarks".equals(component.getAttributeValue("name"))) {
                return migrationFailure(project, "Legacy review file has no ReviewMarks component: " + source);
            }
            LegacyState legacy = XmlSerializer.deserialize(component, LegacyState.class);
            Map<String, LegacyFile> files = new TreeMap<>();
            List<String> warnings = new ArrayList<>();
            if (legacy.files != null) for (var entry : legacy.files.entrySet()) {
                try {
                    LegacyFileState file = entry.getValue();
                    if (file == null || file.ranges == null) throw new IllegalArgumentException("missing ranges");
                    List<ReviewedRange> ranges = new ArrayList<>();
                    for (RangeState range : file.ranges) {
                        if (range == null) throw new IllegalArgumentException("null range");
                        ranges.add(new ReviewedRange(range.start, range.end));
                    }
                    files.put(entry.getKey(), new LegacyFile(file.snapshot, ranges));
                } catch (RuntimeException invalid) {
                    warnings.add("Skipped invalid legacy review for " + entry.getKey() + ": " + invalid.getMessage());
                }
            }
            MigrationResult result = importLegacy(project, files);
            synchronized (lock) {
                ProjectState entry = projectState(project);
                boolean changed = false;
                for (var message : warnings) changed |= warning(entry, message);
                if (changed) modificationCount++;
            }
            List<String> combined = new ArrayList<>(result.warnings());
            combined.addAll(warnings);
            return new MigrationResult(result.importedFiles(), result.importedEvidence(), List.copyOf(combined));
        } catch (Exception failure) {
            return migrationFailure(project, "Could not import legacy review file: " + failure.getMessage());
        }
    }

    /** Import the legacy persisted snapshot before resolving it against any current document. */
    public MigrationResult importLegacy(Project project, Map<String, LegacyFile> legacy) {
        synchronized (lock) {
            if (unsupportedSchema) return new MigrationResult(0, 0, List.copyOf(loadWarnings));
            if (projectState(project).legacyImported) return MigrationResult.empty();
        }
        try {
            Path source = legacyPath(project);
            if (Files.isRegularFile(source)) backupLegacy(project, Files.readAllBytes(source));
        } catch (IOException failure) {
            return migrationFailure(project, "Legacy import postponed because its backup failed: " + failure.getMessage());
        }
        synchronized (lock) {
            ProjectState entry = projectState(project);
            if (entry.legacyImported) return MigrationResult.empty();
            int imported = 0;
            List<String> warnings = new ArrayList<>();
            for (var old : new TreeMap<>(legacy).entrySet()) {
                try {
                    String path = relativePath(old.getKey());
                    LegacyFile legacyFile = old.getValue();
                    if (legacyFile == null || legacyFile.snapshot() == null || legacyFile.ranges() == null) {
                        throw new IllegalArgumentException("missing saved snapshot or ranges");
                    }
                    List<ReviewedRange> ranges = new ArrayList<>();
                    int previousEnd = -1;
                    for (ReviewedRange range : legacyFile.ranges()) {
                        if (range.start() < previousEnd || range.end() > legacyFile.snapshot().length()) {
                            throw new IllegalArgumentException("overlapping or out-of-bounds range");
                        }
                        previousEnd = range.end();
                        int start = normalizedOffset(legacyFile.snapshot(), range.start());
                        int end = normalizedOffset(legacyFile.snapshot(), range.end());
                        if (end > start) ranges.add(new ReviewedRange(start, end));
                    }
                    if (ranges.isEmpty()) continue;
                    FileSnapshot snapshot = FileSnapshot.create(legacyFile.snapshot());
                    FileState file = writableFile(project, entry, path);
                    ReviewHistory history = readHistory(file);
                    String id = "legacy-" + sha256(projectKey(project) + "\n" + path + "\n" + snapshot.id() + "\n" + ranges);
                    if (history.evidence().stream().anyMatch(record -> record.id().equals(id))) continue;
                    Map<String, FileSnapshot> snapshots = new LinkedHashMap<>(history.snapshots());
                    snapshots.put(snapshot.id(), snapshot);
                    List<ReviewEvidence> evidence = new ArrayList<>(history.evidence());
                    long sequence = nextSequence(history);
                    evidence.add(new ReviewEvidence(id, snapshot.id(), List.copyOf(ranges), sequence, 0));
                    writeHistory(file, new ReviewHistory(snapshots, evidence, history.revocations(), history.revision() + 1));
                    imported++;
                } catch (RuntimeException invalid) {
                    warnings.add("Skipped invalid legacy review for " + old.getKey() + ": " + invalid.getMessage());
                }
            }
            entry.legacyImported = true;
            warnings.forEach(message -> warning(entry, message));
            modificationCount++;
            return new MigrationResult(imported, imported, List.copyOf(warnings));
        }
    }

    private MigrationResult migrationFailure(Project project, String message) {
        synchronized (lock) { if (warning(projectState(project), message)) modificationCount++; }
        return new MigrationResult(0, 0, List.of(message));
    }

    private static boolean warning(ProjectState entry, String message) {
        if (entry.warnings.contains(message)) return false;
        entry.warnings.add(message);
        return true;
    }

    private static void readWarning(ProjectState entry, String message) {
        if (!entry.readWarnings.contains(message)) entry.readWarnings.add(message);
    }

    private static long nextSequence(ReviewHistory history) {
        long highest = 0;
        for (ReviewEvidence evidence : history.evidence()) highest = Math.max(highest, evidence.sequence());
        for (Revocation revocation : history.revocations()) highest = Math.max(highest, revocation.sequence());
        return highest + 1;
    }

    private ProjectState projectState(Project project) {
        return state.projects.computeIfAbsent(projectKey(project), ignored -> new ProjectState());
    }

    static String projectKey(Project project) {
        String cached = project.getUserData(PROJECT_KEY);
        if (cached != null) return cached;
        if (project.getBasePath() == null) throw new IllegalArgumentException("Project has no local base directory");
        Path base = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        try { base = base.toRealPath(); } catch (IOException ignored) { }
        String key = base.toString().replace('\\', '/');
        key = System.getProperty("os.name", "").startsWith("Windows") ? key.toLowerCase(Locale.ROOT) : key;
        project.putUserData(PROJECT_KEY, key);
        return key;
    }

    private static String relativePath(String path) {
        if (path == null) throw new IllegalArgumentException("Missing relative file path");
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Absolute file path is not allowed");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid relative file path");
            }
        }
        return normalized;
    }

    private static FileState findFile(Project project, ProjectState entry, String path) {
        path = relativePath(path);
        String id = entry.aliases.get(path);
        FileState file = id == null ? null : entry.files.get(id);
        if (file == null || path.equals(file.path)) return file;
        List<String> existing = existingAliases(project, entry, id);
        // An old alias may reappear after checkout, but a copied file must not inherit history.
        return existing.size() == 1 && existing.contains(path) ? file : null;
    }

    private static List<String> existingAliases(Project project, ProjectState entry, String id) {
        Path base = Path.of(project.getBasePath());
        return entry.aliases.entrySet().stream().filter(alias -> java.util.Objects.equals(alias.getValue(), id))
                .map(Map.Entry::getKey).filter(LegacyReviewHistoryModel::validPath)
                .filter(path -> Files.isRegularFile(base.resolve(path))).toList();
    }

    private static FileState writableFile(Project project, ProjectState entry, String path) {
        path = relativePath(path);
        FileState existing = findFile(project, entry, path);
        if (existing != null && safeRead(entry, existing) != null) return existing;
        FileState created = new FileState();
        created.path = path;
        String id = UUID.randomUUID().toString();
        entry.files.put(id, created);
        entry.aliases.put(path, id);
        return created;
    }

    private static ReviewHistory safeRead(ProjectState entry, FileState file) {
        if (file.quarantined) return null;
        try { return readHistory(file); }
        catch (RuntimeException invalid) {
            file.quarantined = true;
            readWarning(entry, "Quarantined damaged review history for " + file.path + ": " + invalid.getMessage());
            return null;
        }
    }

    private static boolean validPath(String path) {
        try { relativePath(path); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void requireWritableSchema() {
        if (unsupportedSchema) throw new IllegalStateException("Review history uses an unsupported schema; it is read-only");
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static ReviewHistory readHistory(FileState file) {
        if (file.cachedHistory != null) return file.cachedHistory;
        file.cachedHistory = ReviewJournalCodec.read(file);
        return file.cachedHistory;
    }

    private static void writeHistory(FileState file, ReviewHistory history) {
        // Live commands replace an immutable domain value. DTO expansion belongs to
        // background export, rather than every Ctrl+M on the event dispatch thread.
        file.cachedHistory = history;
        file.journalDirty = true;
        file.evidenceIndex = null;
        // Existing evidence is immutable, so prior validated checkpoint references
        // remain valid when evidence/revocations are appended.
        file.invalidCheckpoints.clear();
        file.quarantined = false;
    }

    private static int normalizedOffset(String text, int offset) {
        int removed = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') removed++;
        return offset - removed;
    }

    private static Path legacyPath(Project project) {
        return Path.of(project.getBasePath(), ".idea", "review-marks.xml");
    }

    public static Path backupDirectory() {
        return PathManager.getConfigDir().resolve("review-marks-legacy-backups");
    }

    private void backupLegacy(Project project, byte[] raw) throws IOException {
        Path directory = backupRoot;
        Files.createDirectories(directory);
        Path destination = directory.resolve(sha256(projectKey(project)) + "-" + sha256(raw) + ".xml");
        if (Files.exists(destination)) {
            if (!Arrays.equals(raw, Files.readAllBytes(destination))) throw new IOException("Existing legacy backup differs: " + destination);
            return;
        }
        Path temporary = Files.createTempFile(directory, "legacy-", ".tmp");
        try {
            Files.write(temporary, raw);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public StoreState getState() {
        StoreState detached;
        List<CheckpointExport> exports = new ArrayList<>();
        synchronized (lock) {
            if (unsupportedSchema) return null;
            state.projects.values().forEach(project -> project.files.values().forEach(file -> {
                if (file != null && file.journalDirty) {
                    ReviewJournalCodec.write(file, file.cachedHistory); file.journalDirty = false;
                }
            }));
            detached = ReviewHistoryStateCopies.copy(state);
            state.projects.forEach((projectId, project) -> project.files.forEach((fileId, file) -> {
                if (file != null && (!file.runtimeCheckpoints.isEmpty() || file.runtimePending != null)) {
                    exports.add(new CheckpointExport(file, detached.projects.get(projectId).files.get(fileId),
                            Map.copyOf(file.runtimeCheckpoints), file.runtimePending, readHistory(file)));
                }
            }));
        }
        // XML/checksums run only during export, without blocking input on the store lock.
        for (var export : exports) {
            var index = ProjectionCheckpointCodec.index(export.history());
            if (export.target().checkpoints == null) export.target().checkpoints = new TreeMap<>();
            export.settled().forEach((hash, value) -> {
                var encoded = ProjectionCheckpointCodec.encode(index, value.text(), value.hints());
                export.target().checkpoints.put(hash, encoded);
                var internal = ReviewHistoryStateCopies.checkpoint(encoded);
                synchronized (lock) {
                    if (export.live().runtimeCheckpoints.get(hash) == value) {
                        if (export.live().checkpoints == null) export.live().checkpoints = new TreeMap<>();
                        replaceCheckpoint(export.live(), hash, internal);
                        export.live().runtimeCheckpoints.remove(hash);
                        export.live().validatedCheckpoints.put(internal,
                                ProjectionCheckpointCodec.cached(internal, value.hints()));
                    }
                }
            });
            if (export.pending() != null) {
                var pending = export.pending();
                export.target().pendingCheckpoint = ProjectionCheckpointCodec.encode(index, pending.text(),
                        pending.hints().compactForCheckpoint(pending.text()));
            }
        }
        return detached;
    }

    public long getStateModificationCount() {
        synchronized (lock) { return modificationCount; }
    }

    @Override public @NotNull Resolution getResolution(@NotNull Storage storage, @NotNull StateStorageOperation operation) {
        synchronized (lock) {
            return unsupportedSchema && operation == StateStorageOperation.WRITE ? Resolution.SKIP : Resolution.DO;
        }
    }

    public void loadState(@NotNull StoreState loaded) { installState(loaded, true); }

    void loadDetachedState(StoreState loaded) { installState(loaded, false); }

    private void installState(StoreState loaded, boolean notify) {
        StoreState detached = ReviewHistoryStateCopies.copy(loaded);
        List<String> migrationWarnings = List.of();
        if (detached.schemaVersion == 1) {
            var macros = ApplicationManager.getApplication().getService(PathMacroManager.class);
            migrationWarnings = LegacyMacroRecovery.recover(detached, macros::expandPath);
            detached.schemaVersion = 2;
        }
        synchronized (lock) {
            state = detached;
            modificationCount++;
            loadWarnings.clear();
            loadWarnings.addAll(migrationWarnings);
            unsupportedSchema = state.schemaVersion != 2;
            if (unsupportedSchema) loadWarnings.add("Review history schema " + state.schemaVersion + " is unsupported; no data will be overwritten");
            if (state.projects == null) state.projects = new TreeMap<>();
            state.projects.replaceAll((key, entry) -> {
                if (entry == null) entry = new ProjectState();
                if (entry.files == null) entry.files = new TreeMap<>();
                if (entry.aliases == null) entry.aliases = new TreeMap<>();
                if (entry.warnings == null) entry.warnings = new ArrayList<>();
                return entry;
            });
        }
        var application = ApplicationManager.getApplication();
        if (notify && application != null && !application.isDisposed()) {
            application.getMessageBus().syncPublisher(TOPIC).historyChanged();
        }
    }

    CapturedFile captureFile(Project project, String path) {
        synchronized (lock) {
            var entry = state.projects.get(projectKey(project));
            var file = entry == null ? null : findFile(project, entry, path);
            if (file == null) return null;
            return new CapturedFile(file.path, readHistory(file), Map.copyOf(file.checkpoints),
                    Map.copyOf(file.runtimeCheckpoints), file.runtimePending, file.pendingCheckpoint);
        }
    }

    record CapturedFile(String path, ReviewHistory history, Map<String, CheckpointState> checkpoints,
                        Map<String, RuntimeCheckpoint> runtime, RuntimeCheckpoint pending, CheckpointState savedPending) {
        FileState encode() {
            var file = new FileState(); file.path = path;
            ReviewJournalCodec.write(file, history);
            file.checkpoints.putAll(checkpoints);
            var index = ProjectionCheckpointCodec.index(history);
            runtime.forEach((hash, value) -> {
                file.snapshots.put(hash, value.text());
                file.checkpoints.put(hash, ProjectionCheckpointCodec.encode(index, value.text(), value.hints()));
            });
            if (pending != null) {
                file.snapshots.put(FileSnapshot.create(pending.text()).id(), pending.text());
                file.pendingCheckpoint = ProjectionCheckpointCodec.encode(index, pending.text(),
                        pending.hints().compactForCheckpoint(pending.text()));
            } else file.pendingCheckpoint = savedPending;
            return file;
        }
    }

    public record LegacyFile(String snapshot, List<ReviewedRange> ranges) { }
    public record MigrationResult(int importedFiles, int importedEvidence, List<String> warnings) {
        static MigrationResult empty() { return new MigrationResult(0, 0, List.of()); }
    }
    public static final class StoreState {
        // Default 1 reads existing XML with no explicit version; all new exports write 2.
        public int schemaVersion = 1;
        public Map<String, ProjectState> projects = new TreeMap<>();
        public Map<String, ProjectState> quarantinedProjects = new TreeMap<>();
    }
    public static final class ProjectState {
        @Transient private final List<String> readWarnings = new ArrayList<>();
        public Map<String, FileState> files = new TreeMap<>();
        public Map<String, String> aliases = new TreeMap<>();
        public boolean legacyImported;
        public List<String> warnings = new ArrayList<>();
    }
    public static final class FileState {
        @Transient private ReviewHistory cachedHistory;
        @Transient private boolean journalDirty;
        @Transient private boolean quarantined;
        @Transient private ProjectionCheckpointCodec.EvidenceIndex evidenceIndex;
        @Transient private final Map<CheckpointState, ProjectionCheckpointCodec.ValidatedCheckpoint> validatedCheckpoints = new IdentityHashMap<>();
        @Transient private final Map<CheckpointState, String> invalidCheckpoints = new IdentityHashMap<>();
        @Transient private final Map<String, RuntimeCheckpoint> runtimeCheckpoints = new HashMap<>();
        @Transient private RuntimeCheckpoint runtimePending;
        public String path = "";
        public Map<String, String> snapshots = new TreeMap<>();
        public List<EvidenceState> evidence = new ArrayList<>();
        public List<RevocationState> revocations = new ArrayList<>();
        public Map<String, CheckpointState> checkpoints = new TreeMap<>();
        public CheckpointState pendingCheckpoint;
        public long revision;
    }
    public static final class CheckpointState {
        public String checksum = "";
        public CheckpointPayload payload = new CheckpointPayload();
    }
    public static final class CheckpointPayload {
        public int version = 1;
        public String textHash = "";
        public int textLength;
        public List<CheckpointRefState> refs = new ArrayList<>();
    }
    public static final class CheckpointRefState {
        public String evidenceId = "";
        public int start;
        public int end;
        public boolean authoritative;
        public boolean deleted;
        public boolean allowRematch;
        public RangeState live;
        public String baselineText;
        public DeletedAnchorState anchor;
        public UnresolvedState unresolved;
    }
    public static final class DeletedAnchorState {
        public int offset;
        public int relativeStart;
        public int ordinal;
        public String baselineText;
        public String removedText;
    }
    public static final class UnresolvedState {
        public RangeState range;
        public String reason;
        public String originalText;
        public int relativeStart;
        public int relativeEnd;
        public List<RangeState> excludedRows = new ArrayList<>();
    }
    public static final class EvidenceState {
        public String id = "";
        public String snapshotId = "";
        public List<RangeState> ranges = new ArrayList<>();
        public long sequence;
        public long createdAt;
    }
    public static final class RevocationState {
        public String evidenceId = "";
        public List<RangeState> ranges = new ArrayList<>();
        public long sequence;
    }
    public static final class RangeState {
        public int start;
        public int end;
        public RangeState() { }
        public RangeState(int start, int end) { this.start = start; this.end = end; }
    }
    public static final class LegacyState { public Map<String, LegacyFileState> files = new TreeMap<>(); }
    public static final class PreparedCheckpoint {
        final ReviewHistory expectedHistory;
        final String text;
        final ProjectionHints hints;
        final CheckpointState encoded;
        private PreparedCheckpoint(ReviewHistory history, String text, ProjectionHints hints, CheckpointState encoded) {
            expectedHistory = history; this.text = text; this.hints = hints; this.encoded = encoded;
        }
    }
    private record RuntimeCheckpoint(String text, ProjectionHints hints) { }
    private record CheckpointExport(FileState live, FileState target, Map<String, RuntimeCheckpoint> settled,
                                    RuntimeCheckpoint pending, ReviewHistory history) { }
    @Tag("FileState")
    public static final class LegacyFileState {
        public String snapshot;
        public List<RangeState> ranges = new ArrayList<>();
    }
}
