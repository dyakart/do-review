package dev.doreview.ide;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.JsonParseException;
import com.intellij.openapi.application.ApplicationManager;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ReviewHistory;
import dev.doreview.core.ReviewedRange;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Worktree-scoped durable history. Portable immutable events are authoritative;
 * SQLite indexes only the event set currently present in this checkout.
 * All public operations are blocking and must be scheduled outside the EDT.
 */
public final class SharedHistoryRepository implements AutoCloseable {
    private static final int SCHEMA = 1;
    private static final int MAX_EVENT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 128 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping()
            .addSerializationExclusionStrategy(new RuntimeFields())
            .addDeserializationExclusionStrategy(new RuntimeFields()).create();
    private static final Gson PORTABLE_JSON = new GsonBuilder().disableHtmlEscaping()
            .addSerializationExclusionStrategy(new RuntimeFields())
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override public boolean shouldSkipField(FieldAttributes field) {
                    return field.getName().equals("sequence") && (field.getDeclaringClass() == ReviewHistoryStore.EvidenceState.class
                            || field.getDeclaringClass() == ReviewHistoryStore.RevocationState.class);
                }
                @Override public boolean shouldSkipClass(Class<?> type) { return false; }
            }).create();
    private final Path root;
    private final Path shared;
    private final Path database;
    private final Runnable publicationBoundary;
    private Connection connection;
    private final Map<Path, Fingerprint> scanned = new HashMap<>();
    private final Map<String, CachedFile> cache = new LinkedHashMap<>(16, .75f, true);
    private String checkoutToken = "";
    private String gitFingerprint = "";
    private String eventToken = "";
    private long externalDataVersion;
    private final String writerId = UUID.randomUUID().toString();
    private String lastPublishedEventId;
    private long decodedEventFiles;
    private long readSnapshotFiles;
    private long indexedEvents;
    private long lastScanNanos;
    private boolean closed;

    public record Author(String name, String email) {
        public Author {
            name = name == null || name.isBlank() ? "Unknown author" : name.trim();
            email = email == null ? "" : email.trim();
            if (name.length() > 1024 || email.length() > 1024 || name.indexOf('\0') >= 0 || email.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid review author");
        }
        public static Author imported() { return new Author("Imported history", ""); }
    }
    public record LoadedFile(String fileId, ReviewHistoryStore.FileState state, Set<String> aliases,
                             Map<String, Author> evidenceAuthors, Set<String> frontier) { }
    public record Version(String snapshotId, long createdAt, Author author) { }
    public record ScanStatistics(long decodedEventFiles, long readSnapshotFiles, long indexedEvents, long elapsedNanos) { }
    public synchronized ScanStatistics lastScanStatistics() { requireOpen(); return new ScanStatistics(decodedEventFiles, readSnapshotFiles, indexedEvents, lastScanNanos); }
    private record Fingerprint(long size, long modified, String digest) { }
    private record DirectoryStamp(boolean exists, long modified, String fileKey) { }
    private record PublicationFence(long databaseVersion, CheckoutState checkout,
                                    Map<Path, DirectoryStamp> directories, Map<Path, Set<String>> expectedChildren,
                                    Map<Path, Fingerprint> currentFileInputs, Set<Path> ownFiles) { }
    private record CachedFile(String token, LoadedFile value, long weight) { }
    private static final class RuntimeFields implements ExclusionStrategy {
        @Override public boolean shouldSkipField(FieldAttributes field) {
            return field.getDeclaringClass() == ReviewHistoryStore.FileState.class && !field.hasModifier(Modifier.PUBLIC);
        }
        @Override public boolean shouldSkipClass(Class<?> type) { return false; }
    }

    /** Portable schema. Local sequence numbers and absolute paths are deliberately absent. */
    public static final class Event {
        public int version = 1;
        public String id;
        public String fileId;
        public String path;
        public List<String> aliases = new ArrayList<>();
        public String authorName;
        public String authorEmail;
        public long createdAt;
        public List<String> parents = new ArrayList<>();
        public List<ReviewHistoryStore.EvidenceState> evidence = new ArrayList<>();
        public List<ReviewHistoryStore.RevocationState> revocations = new ArrayList<>();
        public Map<String, ReviewHistoryStore.CheckpointState> checkpoints = new TreeMap<>();
        public Set<String> snapshots = new TreeSet<>();
    }
    private static final class PendingState {
        public int version = 1;
        public ReviewHistoryStore.FileState state;
        public Set<String> parents = new TreeSet<>();
        public Set<String> snapshotIds = new TreeSet<>();
        public long createdAt;
    }

    public SharedHistoryRepository(Path worktreeRoot, Path databasePath) {
        this(worktreeRoot, databasePath, null);
    }

    /** Package-local fault injection for publication/checkout interleaving tests. */
    SharedHistoryRepository(Path worktreeRoot, Path databasePath, Runnable publicationBoundary) {
        backgroundOnly();
        root = ReviewRepositoryPaths.canonicalRoot(worktreeRoot);
        shared = root.resolve(".doreview");
        database = databasePath.toAbsolutePath().normalize();
        this.publicationBoundary = publicationBoundary;
        try {
            Files.createDirectories(database.getParent());
            Class.forName("org.sqlite.JDBC");
            var config = new SQLiteConfig();
            config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            config.setBusyTimeout(5000);
            connection = DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT sqlite_version()")) {
                if (!rows.next() || !supportedSqliteVersion(rows.getString(1))) throw new IllegalStateException("DoReview requires SQLite 3.51.3 or newer");
            }
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            initialize();
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
            }
            externalDataVersion = dataVersion();
            rescan();
        } catch (Exception failure) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            throw problem("Cannot open review database", failure);
        }
    }

    public Path databasePath() { return database; }
    public Path worktreeRoot() { return root; }

    private void initialize() throws SQLException {
        transaction(() -> {
            // The configured IMMEDIATE transaction reserves the writer before reading the
            // version; independent IDE processes cannot both initialize a new database.
            int schema;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) { rows.next(); schema = rows.getInt(1); }
            if (schema > SCHEMA || schema < 0) throw new IllegalStateException("Unsupported DoReview database schema " + schema);
            if (schema == SCHEMA) return;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                rows.next();
                if (rows.getInt(1) != 0) throw new IllegalStateException("Unknown review database: preserved without changes");
            }
            execute("CREATE TABLE events(id TEXT PRIMARY KEY,digest TEXT NOT NULL,payload TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 0,file_id TEXT NOT NULL,path TEXT NOT NULL,author TEXT NOT NULL,created_at INTEGER NOT NULL)");
            execute("CREATE INDEX events_file_active ON events(file_id,active)");
            execute("CREATE INDEX events_path_active ON events(path,active)");
            execute("CREATE TABLE dependencies(event_id TEXT NOT NULL,parent_id TEXT NOT NULL,PRIMARY KEY(event_id,parent_id))");
            execute("CREATE INDEX dependencies_parent ON dependencies(parent_id,event_id)");
            execute("CREATE TABLE snapshots(hash TEXT PRIMARY KEY,encoding INTEGER NOT NULL,data BLOB NOT NULL)");
            execute("CREATE TABLE event_snapshots(event_id TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(event_id,hash))");
            execute("CREATE TABLE evidence(file_id TEXT NOT NULL,evidence_id TEXT NOT NULL,event_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(file_id,evidence_id,event_id))");
            execute("CREATE TABLE revocations(file_id TEXT NOT NULL,event_id TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(event_id,ordinal))");
            execute("CREATE TABLE checkpoints(file_id TEXT NOT NULL,event_id TEXT NOT NULL,hash TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(event_id,hash))");
            execute("CREATE TABLE aliases(file_id TEXT NOT NULL,path TEXT NOT NULL,event_id TEXT NOT NULL,PRIMARY KEY(file_id,path,event_id))");
            execute("CREATE INDEX aliases_path ON aliases(path)");
            execute("CREATE TABLE pending(file_id TEXT NOT NULL,path TEXT NOT NULL,checkout_token TEXT NOT NULL,writer_id TEXT NOT NULL,payload TEXT NOT NULL,author TEXT NOT NULL,PRIMARY KEY(file_id,checkout_token,writer_id))");
            execute("PRAGMA user_version=" + SCHEMA);
        });
    }

    /** Reconciles additions AND removals; a branch switch cannot retain absent events. */
    public synchronized boolean rescan() {
        requireOpen();
        long started = System.nanoTime(); decodedEventFiles = 0; readSnapshotFiles = 0; indexedEvents = 0;
        try {
            long version = dataVersion();
            if (version != externalDataVersion) cache.clear();
            if (Files.isDirectory(shared)) try (var directories = Files.list(shared)) {
                if (directories.anyMatch(path -> path.getFileName().toString().matches("v[0-9]+") && !path.getFileName().toString().equals("v1")))
                    throw new IllegalStateException("Unsupported shared review history version; existing files were preserved");
            }
            Path eventsRoot = shared.resolve("v1/events");
            var safeDirectories = new HashSet<Path>();
            var diskEvents = new TreeMap<String, Path>();
            var metadata = new HashMap<Path, Fingerprint>();
            if (Files.exists(eventsRoot)) {
                safeSharedPath(eventsRoot);
                try (var paths = Files.walk(eventsRoot)) {
                    for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        safeSharedPath(path, safeDirectories);
                        String id = path.getFileName().toString().replaceFirst("\\.json$", "");
                        if (!eventPath(id).equals(path) || diskEvents.put(id, path) != null) throw new IllegalStateException("Review event filename mismatch");
                        Fingerprint old = scanned.get(path);
                        metadata.put(path, fingerprint(path, MAX_EVENT_BYTES, old == null ? "" : old.digest));
                    }
                }
            }
            CheckoutState checkout = readCheckoutState();
            String branch = checkout.token;
            // Periodic refreshes inspect metadata only. No JSON decoding, snapshot reads or
            // full-history SQL queries occur while the portable tree remains unchanged.
            Path snapshotRoot = shared.resolve("v1/snapshots");
            var quick = new HashMap<>(metadata);
            for (var old : scanned.entrySet()) if (old.getKey().startsWith(snapshotRoot)) {
                Path path = old.getKey(); safeSharedPath(path, safeDirectories);
                quick.put(path, Files.exists(path) ? fingerprint(path, MAX_SNAPSHOT_BYTES, old.getValue().digest) : new Fingerprint(-1, -1, old.getValue().digest));
            }
            if (!eventToken.isEmpty() && quick.equals(scanned) && branch.equals(checkoutToken) && checkout.fingerprint.equals(gitFingerprint)) {
                externalDataVersion = version;
                return false;
            }
            var events = new TreeMap<String, Event>();
            var digests = new HashMap<String, String>();
            var previouslyActive = new HashSet<String>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,file_id,path,digest FROM events WHERE active=1")) {
                while (rows.next()) {
                    String id = rows.getString(1); previouslyActive.add(id);
                    if (!diskEvents.containsKey(id)) continue;
                    var event = new Event(); event.id = id; event.fileId = rows.getString(2); event.path = rows.getString(3);
                    events.put(id, event); digests.put(id, rows.getString(4));
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT d.event_id,d.parent_id FROM dependencies d JOIN events e ON e.id=d.event_id WHERE e.active=1")) {
                while (rows.next()) { Event event = events.get(rows.getString(1)); if (event != null) event.parents.add(rows.getString(2)); }
            }
            var payloads = new HashMap<String, String>();
            var parsed = new HashMap<String, Event>();
            var fingerprints = new HashMap<Path, Fingerprint>();
            for (var entry : diskEvents.entrySet()) {
                String id = entry.getKey(); Path path = entry.getValue();
                Fingerprint observed = metadata.get(path), old = scanned.get(path);
                if (events.containsKey(id) && observed.equals(old)) { fingerprints.put(path, observed); continue; }
                String payload = FileSnapshot.normalize(Files.readString(path, StandardCharsets.UTF_8));
                decodedEventFiles++;
                Event event = parseEvent(payload);
                if (!id.equals(event.id)) throw new IllegalStateException("Review event filename mismatch");
                String digest = ReviewRepositoryPaths.sha256(payload);
                String indexed = digests.get(id);
                if (indexed == null) indexed = indexedDigest(id);
                if (indexed != null && !indexed.equals(digest)) throw new IllegalStateException("Conflicting immutable review event " + id);
                if (indexed == null) payloads.put(id, payload);
                parsed.put(id, event); events.put(id, event);
                fingerprints.put(path, new Fingerprint(observed.size, observed.modified, digest));
            }
            List<Event> ordered = topological(events);
            var snapshotIds = new TreeSet<String>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT s.event_id,s.hash FROM event_snapshots s JOIN events e ON e.id=s.event_id WHERE e.active=1")) {
                while (rows.next()) if (diskEvents.containsKey(rows.getString(1))) snapshotIds.add(rows.getString(2));
            }
            parsed.values().forEach(event -> snapshotIds.addAll(event.snapshots));
            for (String hash : snapshotIds) {
                Path path = snapshotPath(hash); safeSharedPath(path, safeDirectories);
                Fingerprint observed = fingerprint(path, MAX_SNAPSHOT_BYTES, hash);
                Fingerprint old = scanned.get(path);
                if (!observed.equals(old)) { readSnapshotFiles++; new FileSnapshot(hash, Files.readString(path, StandardCharsets.UTF_8)); }
                fingerprints.put(path, observed);
            }
            String token = ReviewRepositoryPaths.sha256(String.join("\n", events.keySet()));
            boolean changed = !token.equals(eventToken) || !branch.equals(checkoutToken) || !checkout.fingerprint.equals(gitFingerprint) || !fingerprints.equals(scanned);
            if (changed) {
                transaction(() -> {
                    for (String id : previouslyActive) if (!events.containsKey(id)) {
                        try (var update = connection.prepareStatement("UPDATE events SET active=0 WHERE id=?")) { update.setString(1, id); update.executeUpdate(); }
                    }
                    for (Event event : ordered) {
                        if (payloads.containsKey(event.id) && indexedDigest(event.id) == null) index(event, payloads.get(event.id));
                        if (!previouslyActive.contains(event.id)) {
                            try (var update = connection.prepareStatement("UPDATE events SET active=1 WHERE id=?")) { update.setString(1, event.id); update.executeUpdate(); }
                        }
                    }
                });
                cache.clear();
                scanned.clear(); scanned.putAll(fingerprints);
                eventToken = token;
                checkoutToken = branch;
                gitFingerprint = checkout.fingerprint;
            }
            externalDataVersion = dataVersion();
            return changed;
        } catch (Exception failure) {
            cache.clear();
            // Do not expose earlier green data while portable history is incomplete or corrupt.
            try { execute("UPDATE events SET active=0"); } catch (SQLException ignored) { }
            scanned.clear(); checkoutToken = "invalid";
            throw problem("Cannot read shared review history", failure);
        } finally { lastScanNanos = System.nanoTime() - started; }
    }

    public synchronized String checkoutToken() { requireOpen(); return checkoutToken; }

    public synchronized LoadedFile load(String relativePath) {
        requireOpen();
        String path = ReviewRepositoryPaths.validateRelativePath(relativePath);
        invalidateExternal();
        try {
            var ids = new TreeSet<String>();
            try (var statement = connection.prepareStatement("SELECT DISTINCT a.file_id FROM aliases a JOIN events e ON e.id=a.event_id WHERE a.path=? AND e.active=1")) {
                statement.setString(1, path);
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
            }
            if (ids.isEmpty()) return null;
            ids.removeIf(id -> aliasRecreated(id, path));
            if (ids.isEmpty()) return null;
            if (ids.size() > 1) throw new IllegalStateException("Ambiguous review file identity for " + path);
            return loadId(ids.first(), path);
        } catch (SQLException failure) { throw problem("Cannot load review history", failure); }
    }

    /** New copies at a previously renamed path receive a different deterministic identity. */
    public synchronized String fileIdFor(String path) {
        requireOpen(); ReviewRepositoryPaths.validateRelativePath(path);
        LoadedFile existing = load(path);
        if (existing != null) return existing.fileId;
        String candidate = ReviewRepositoryPaths.sha256("file\n" + path);
        try {
            var occupied = new TreeSet<String>();
            try (var statement = connection.prepareStatement("SELECT DISTINCT a.file_id FROM aliases a JOIN events e ON e.id=a.event_id WHERE a.path=? AND e.active=1")) {
                statement.setString(1, path);
                try (var rows = statement.executeQuery()) { while (rows.next()) occupied.add(rows.getString(1)); }
            }
            if (!occupied.isEmpty()) {
                var frontier = new TreeSet<String>(); for (String id : occupied) frontier.addAll(tips(id));
                candidate = ReviewRepositoryPaths.sha256("file\n" + path + "\nrecreated\n" + String.join("\n", frontier));
            }
            return candidate;
        } catch (SQLException failure) { throw problem("Cannot allocate review file identity", failure); }
    }

    private boolean aliasRecreated(String fileId, String path) {
        if (!Files.exists(root.resolve(path))) return false;
        try {
            Set<String> tipIds = Set.copyOf(tips(fileId));
            var currentPaths = new TreeSet<String>();
            for (String id : tipIds) try (var statement = connection.prepareStatement("SELECT path FROM events WHERE id=?")) {
                statement.setString(1, id); try (var rows = statement.executeQuery()) { if (rows.next()) currentPaths.add(rows.getString(1)); }
            }
            if (currentPaths.size() > 1 && currentPaths.stream().anyMatch(current -> !current.equals(path) && Files.exists(root.resolve(current)))) return true;
            return !currentPaths.contains(path) && currentPaths.stream().anyMatch(current -> Files.exists(root.resolve(current)));
        } catch (SQLException failure) { throw problem("Cannot resolve review file alias", failure); }
    }

    private LoadedFile loadId(String fileId, String path) throws SQLException {
        CachedFile cached = cache.get(fileId);
        if (cached != null && cached.token.equals(eventToken)) return copyLoaded(cached.value, path);
        var events = new TreeMap<String, Event>();
        try (var statement = connection.prepareStatement("SELECT payload FROM events WHERE file_id=? AND active=1")) {
            statement.setString(1, fileId);
            try (var rows = statement.executeQuery()) { while (rows.next()) { Event event = parseEvent(rows.getString(1)); events.put(event.id, event); } }
        }
        if (events.isEmpty()) return null;
        LoadedFile loaded = materialize(fileId, path, topologicalSubset(events));
        long weight = loaded.state.snapshots.values().stream().mapToLong(text -> 2L * text.length()).sum()
                + loaded.state.evidence.stream().mapToLong(proof -> 256L + 32L * proof.ranges.size()).sum()
                + loaded.state.checkpoints.values().stream().mapToLong(checkpoint -> 256L + 256L * checkpoint.payload.refs.size()).sum();
        if (weight <= 64L * 1024 * 1024) cache.put(fileId, new CachedFile(eventToken, loaded, weight));
        while (cache.size() > 16 || cache.values().stream().mapToLong(CachedFile::weight).sum() > 64L * 1024 * 1024) cache.remove(cache.keySet().iterator().next());
        return copyLoaded(loaded, path);
    }

    public synchronized Map<String, String> listFiles() {
        requireOpen(); invalidateExternal();
        var result = new TreeMap<String, String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DISTINCT a.path,a.file_id FROM aliases a JOIN events e ON e.id=a.event_id WHERE e.active=1 ORDER BY a.path")) {
            while (rows.next()) {
                String old = result.put(rows.getString(1), rows.getString(2));
                if (old != null && !old.equals(rows.getString(2))) throw new IllegalStateException("Ambiguous review path");
            }
            return Map.copyOf(result);
        } catch (SQLException failure) { throw problem("Cannot list review history", failure); }
    }

    /** Writes one immutable operation batch; unchanged evidence and snapshots are not republished. */
    public synchronized LoadedFile publish(String fileId, String path, ReviewHistoryStore.FileState before,
                                           ReviewHistoryStore.FileState after, Author author) {
        return publish(fileId, path, before, after, author, null);
    }

    public synchronized LoadedFile publish(String fileId, String path, ReviewHistoryStore.FileState before,
                                           ReviewHistoryStore.FileState after, Author author, Set<String> observedParents) {
        return publish(fileId, path, before, after, author, observedParents, null);
    }

    public synchronized LoadedFile publish(String fileId, String path, ReviewHistoryStore.FileState before,
                                           ReviewHistoryStore.FileState after, Author author, Set<String> observedParents,
                                           String expectedCheckoutToken) {
        requireOpen();
        lastPublishedEventId = null;
        path = ReviewRepositoryPaths.validateRelativePath(path);
        Objects.requireNonNull(after); Objects.requireNonNull(author);
        rescan();
        String publicationCheckout = checkoutToken;
        if (expectedCheckoutToken != null && !expectedCheckoutToken.equals(publicationCheckout))
            throw new IllegalStateException("Pending review belongs to an earlier checkout and was retained locally; return to that checkout to publish it");
        try {
            LoadedFile existing = load(path);
            if (before == null && existing != null) before = existing.state;
            if (fileId == null) fileId = existing == null ? fileIdFor(path) : existing.fileId;
            validateIdentifier(fileId);
            if (existing != null && !existing.fileId.equals(fileId)) throw new IllegalArgumentException("File identity conflicts with existing path");
            var event = new Event();
            event.id = UUID.randomUUID().toString(); event.fileId = fileId; event.path = path;
            event.aliases.add(path);
            if (before != null && before.path != null && !before.path.isBlank() && !before.path.equals(path))
                event.aliases.add(ReviewRepositoryPaths.validateRelativePath(before.path));
            event.authorName = author.name; event.authorEmail = author.email; event.createdAt = System.currentTimeMillis();
            event.parents = observedParents == null ? tips(fileId) : new ArrayList<>(new TreeSet<>(observedParents));
            for (String parent : event.parents) {
                String payload = indexedPayload(parent, null);
                if (payload == null || !parseEvent(payload).fileId.equals(fileId) || !active(parent)) throw new IllegalStateException("Observed review frontier is absent from current checkout");
            }
            validateFile(after);
            var previousEvidence = new HashMap<String, String>();
            if (before != null) for (var proof : before.evidence) previousEvidence.put(proof.id, canonicalEvidence(proof));
            for (var proof : after.evidence) {
                String previous = previousEvidence.get(proof.id);
                String current = canonicalEvidence(proof);
                if (previous != null && !previous.equals(current)) throw new IllegalArgumentException("Existing review evidence is immutable");
                if (previous == null) {
                    var value = copy(proof, ReviewHistoryStore.EvidenceState.class); value.sequence = 0;
                    event.evidence.add(value); event.snapshots.add(proof.snapshotId);
                }
            }
            var previousRevocations = new HashSet<String>();
            if (before != null) before.revocations.forEach(revocation -> previousRevocations.add(canonicalRevocation(revocation)));
            for (var revocation : after.revocations) if (!previousRevocations.contains(canonicalRevocation(revocation))) {
                var value = copy(revocation, ReviewHistoryStore.RevocationState.class); value.sequence = 0;
                event.revocations.add(value);
            }
            for (var checkpoint : after.checkpoints.entrySet()) {
                var old = before == null ? null : before.checkpoints.get(checkpoint.getKey());
                if (!JSON.toJson(checkpoint.getValue()).equals(JSON.toJson(old))) {
                    event.checkpoints.put(checkpoint.getKey(), copy(checkpoint.getValue(), ReviewHistoryStore.CheckpointState.class));
                    if (after.snapshots.containsKey(checkpoint.getKey())) event.snapshots.add(checkpoint.getKey());
                }
            }
            if (after.pendingCheckpoint != null) {
                String hash = after.pendingCheckpoint.payload.textHash;
                var old = before == null ? null : before.checkpoints.get(hash);
                if (before != null && before.pendingCheckpoint != null && hash.equals(before.pendingCheckpoint.payload.textHash)) old = before.pendingCheckpoint;
                if (!JSON.toJson(after.pendingCheckpoint).equals(JSON.toJson(old))) {
                    event.checkpoints.put(hash, copy(after.pendingCheckpoint, ReviewHistoryStore.CheckpointState.class));
                    if (after.snapshots.containsKey(hash)) event.snapshots.add(hash);
                }
            }
            boolean pathChanged = before != null && !path.equals(before.path);
            if (event.evidence.isEmpty() && event.revocations.isEmpty() && event.checkpoints.isEmpty() && !pathChanged) return existing;
            // References to prior evidence establish causality even if its local revision differs.
            Map<String, String> snapshots = after.snapshots;
            PublicationFence fence = publicationFence(event);
            for (String id : event.snapshots) {
                String text = snapshots.get(id);
                if (text == null) text = snapshot(id);
                new FileSnapshot(id, text);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("Review snapshot exceeds size limit");
                writeImmutable(snapshotPath(id), bytes);
            }
            ensureAttributes();
            String payload = PORTABLE_JSON.toJson(event) + "\n";
            parseEvent(payload);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            if (payloadBytes.length > MAX_EVENT_BYTES) throw new IllegalArgumentException("Review event exceeds size limit");
            if (!publicationCheckout.equals(readCheckoutState().token)) throw new IllegalStateException("Checkout changed before review publication");
            Path publishedPath = eventPath(event.id);
            writeImmutable(publishedPath, payloadBytes);
            if (publicationBoundary != null) publicationBoundary.run();
            if (!publicationCheckout.equals(readCheckoutState().token)) {
                // This command owns this newly generated UUID. Keep its durable pending row
                // instead of leaving an old-branch operation in the replacement checkout.
                if (java.util.Arrays.equals(payloadBytes, Files.readAllBytes(publishedPath))) Files.delete(publishedPath);
                throw new IllegalStateException("Checkout changed during review publication; pending work was retained");
            }
            lastPublishedEventId = event.id;
            boolean ownIndexed;
            try { ownIndexed = indexOwnPublication(event, payload, fence); }
            catch (IOException | IllegalArgumentException concurrentChange) { ownIndexed = false; }
            if (!ownIndexed) rescan();
            return loadId(fileId, path);
        } catch (Exception failure) { throw problem("Cannot save shared review history", failure); }
    }

    public synchronized String lastPublishedEventId() { requireOpen(); return lastPublishedEventId; }

    /** A successful explicit publication already includes this writer's local provenance. */
    public synchronized void ackPending(String fileId, String token) {
        requireOpen();
        try (var statement = connection.prepareStatement("DELETE FROM pending WHERE file_id=? AND checkout_token=? AND writer_id=?")) {
            statement.setString(1, fileId); statement.setString(2, token); statement.setString(3, writerId); statement.executeUpdate();
        } catch (SQLException failure) { throw problem("Cannot acknowledge published provenance", failure); }
    }
    public synchronized void acknowledgePending(String fileId, String token) { ackPending(fileId, token); }

    /** Durable local state is checkout-bound and is never silently exported into another branch. */
    public synchronized void savePending(String fileId, String path, ReviewHistoryStore.FileState state, Author author, String token) {
        savePending(fileId, path, state, author, token, null);
    }

    public synchronized void savePending(String fileId, String path, ReviewHistoryStore.FileState state, Author author, String token, Set<String> observedParents) {
        requireOpen(); validateIdentifier(fileId); ReviewRepositoryPaths.validateRelativePath(path); validateFile(state);
        Objects.requireNonNull(author); Objects.requireNonNull(token);
        try {
            var pending = new PendingState(); pending.version = 2; pending.state = copyFile(state);
            pending.snapshotIds.addAll(state.snapshots.keySet()); pending.state.snapshots.clear();
            pending.createdAt = System.currentTimeMillis();
            pending.parents.addAll(observedParents == null ? tips(fileId) : observedParents);
            transaction(() -> {
                retainOrphanPending(fileId, token);
                putMissingSnapshots(state.snapshots);
                try (var statement = connection.prepareStatement("INSERT INTO pending(file_id,path,checkout_token,writer_id,payload,author) VALUES(?,?,?,?,?,?) ON CONFLICT(file_id,checkout_token,writer_id) DO UPDATE SET path=excluded.path,payload=excluded.payload,author=excluded.author")) {
                    statement.setString(1, fileId); statement.setString(2, path); statement.setString(3, token);
                    statement.setString(4, writerId); statement.setString(5, JSON.toJson(pending)); statement.setString(6, JSON.toJson(author)); statement.executeUpdate();
                }
            });
        } catch (SQLException failure) { throw problem("Cannot save pending review provenance", failure); }
    }

    private void putMissingSnapshots(Map<String, String> snapshots) throws SQLException, IOException {
        var missing = new HashSet<>(snapshots.keySet());
        var ids = new ArrayList<>(missing);
        for (int start = 0; start < ids.size(); start += 256) {
            var batch = ids.subList(start, Math.min(start + 256, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            try (var query = connection.prepareStatement("SELECT hash FROM snapshots WHERE hash IN (" + placeholders + ")")) {
                for (int i = 0; i < batch.size(); i++) query.setString(i + 1, batch.get(i));
                try (var rows = query.executeQuery()) { while (rows.next()) missing.remove(rows.getString(1)); }
            }
        }
        for (String hash : missing) try (var insert = connection.prepareStatement("INSERT INTO snapshots(hash,encoding,data) VALUES(?,1,?)")) {
            insert.setString(1, hash); insert.setBytes(2, gzip(snapshots.get(hash))); insert.executeUpdate();
        }
    }

    private PendingState decodePending(String payload) {
        PendingState pending = JSON.fromJson(payload, PendingState.class);
        validatePendingHeader(pending);
        if (pending.version == 2) {
            if (!pending.state.snapshots.isEmpty() || pending.snapshotIds == null) throw new IllegalArgumentException("Invalid pending snapshot references");
            for (String hash : pending.snapshotIds) {
                validateHash(hash);
                String text = pendingSnapshot(hash);
                if (text == null) throw new IllegalArgumentException("Missing durable pending snapshot " + hash);
                pending.state.snapshots.put(hash, text);
            }
        }
        validateFile(pending.state);
        return pending;
    }

    private String pendingSnapshot(String hash) {
        IllegalStateException damaged = null;
        try {
            String text = snapshot(hash);
            if (text != null) return text;
        } catch (IllegalStateException failure) {
            if (failure.getCause() instanceof UnsupportedSnapshotEncoding) throw failure;
            damaged = failure;
        }
        try {
            Path portable = snapshotPath(hash); safeSharedPath(portable);
            fingerprint(portable, MAX_SNAPSHOT_BYTES, hash);
            String text = Files.readString(portable, StandardCharsets.UTF_8);
            new FileSnapshot(hash, text); // Exact LF and SHA-256 validation precedes cache repair.
            byte[] compressed = gzip(text);
            transaction(() -> {
                try (var query = connection.prepareStatement("SELECT encoding FROM snapshots WHERE hash=?")) {
                    query.setString(1, hash);
                    try (var rows = query.executeQuery()) { if (rows.next() && rows.getInt(1) != 1) throw new UnsupportedSnapshotEncoding(rows.getInt(1)); }
                }
                try (var repair = connection.prepareStatement("INSERT INTO snapshots(hash,encoding,data) VALUES(?,1,?) ON CONFLICT(hash) DO UPDATE SET data=excluded.data")) {
                    repair.setString(1, hash); repair.setBytes(2, compressed); repair.executeUpdate();
                }
            });
            return text;
        } catch (Exception unavailable) {
            if (damaged != null) unavailable.addSuppressed(damaged);
            throw problem("Cannot restore durable pending snapshot " + hash, unavailable);
        }
    }

    private static final class UnsupportedSnapshotEncoding extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private UnsupportedSnapshotEncoding(int encoding) { super("Unknown snapshot compression " + encoding); }
    }

    private void retainOrphanPending(String fileId, String token) throws SQLException {
        String payload = null;
        try (var query = connection.prepareStatement("SELECT payload FROM pending WHERE file_id=? AND checkout_token=? AND writer_id=?")) {
            query.setString(1, fileId); query.setString(2, token); query.setString(3, writerId);
            try (var rows = query.executeQuery()) { if (rows.next()) payload = rows.getString(1); }
        }
        if (payload == null) return;
        PendingState previous = JSON.fromJson(payload, PendingState.class);
        validatePendingHeader(previous);
        boolean orphan = false;
        for (String parent : previous.parents) if (!active(parent)) { orphan = true; break; }
        if (!orphan) return;
        try (var update = connection.prepareStatement("UPDATE pending SET writer_id=? WHERE file_id=? AND checkout_token=? AND writer_id=? AND payload=?")) {
            update.setString(1, "retained-" + UUID.randomUUID()); update.setString(2, fileId); update.setString(3, token);
            update.setString(4, writerId); update.setString(5, payload); update.executeUpdate();
        }
    }

    public synchronized LoadedFile flushPending(String fileId, String token) {
        return flushPendingRows(fileId, token, false).loaded;
    }

    private record PendingFlush(LoadedFile loaded, int retained) { }
    private PendingFlush flushPendingRows(String fileId, String token, boolean skipOrphans) {
        requireOpen();
        try {
            List<PendingRow> rows = pendingRows(fileId, token);
            if (rows.isEmpty()) return new PendingFlush(null, 0);
            rescan();
            if (!token.equals(checkoutToken)) throw new IllegalStateException("Pending provenance belongs to another checkout");
            LoadedFile result = null;
            int retained = 0;
            for (PendingRow row : rows) {
                PendingState pending = decodePending(row.payload);
                boolean orphan = false;
                for (String parent : pending.parents) if (!active(parent)) { orphan = true; break; }
                if (orphan && skipOrphans) { retained++; continue; }
                LoadedFile before = load(row.path);
                result = publish(fileId, row.path, before == null ? null : before.state, pending.state, row.author, pending.parents, token);
                try (var delete = connection.prepareStatement("DELETE FROM pending WHERE file_id=? AND checkout_token=? AND writer_id=? AND payload=?")) {
                    delete.setString(1, fileId); delete.setString(2, token); delete.setString(3, row.writer); delete.setString(4, row.payload); delete.executeUpdate();
                }
            }
            return new PendingFlush(result, retained);
        } catch (SQLException failure) { throw problem("Cannot publish pending review provenance", failure); }
    }

    public synchronized ReviewHistoryStore.FileState pending(String fileId, String token) {
        requireOpen();
        try {
            List<PendingRow> rows = pendingRows(fileId, token);
            if (rows.isEmpty()) return null;
            if (!token.equals(checkoutToken)) return copyFile(decodePending(rows.getFirst().payload).state);
            var events = new TreeMap<String, Event>();
            try (var statement = connection.prepareStatement("SELECT payload FROM events WHERE file_id=? AND active=1")) {
                statement.setString(1, fileId);
                try (var values = statement.executeQuery()) { while (values.next()) { Event event = parseEvent(values.getString(1)); events.put(event.id, event); } }
            }
            var snapshots = new TreeMap<String, String>(); var pendingTargets = new TreeSet<String>();
            var orphanEvidence = new HashSet<String>();
            boolean recovered = false;
            for (PendingRow row : rows) {
                var pending = decodePending(row.payload);
                // A reset/rebase may remove this record's public lineage while retaining the
                // branch name. Preserve it for recovery without applying it to a new lineage.
                if (pending.parents.stream().anyMatch(parent -> !events.containsKey(parent))) {
                    pending.state.evidence.forEach(proof -> orphanEvidence.add(proof.id));
                    continue;
                }
                recovered = true;
                pending.state.snapshots.forEach((hash, text) -> {
                    String old = snapshots.put(hash, text); if (old != null && !old.equals(text)) throw new IllegalStateException("Conflicting pending snapshot");
                });
                var event = new Event();
                event.id = UUID.nameUUIDFromBytes(("pending\n" + row.writer + "\n" + token).getBytes(StandardCharsets.UTF_8)).toString();
                event.fileId = fileId; event.path = row.path; event.parents = new ArrayList<>(pending.parents);
                event.authorName = row.author.name; event.authorEmail = row.author.email; event.createdAt = pending.createdAt;
                event.evidence = pending.state.evidence; event.revocations = pending.state.revocations;
                event.snapshots.addAll(pending.state.snapshots.keySet()); event.checkpoints.putAll(pending.state.checkpoints);
                if (pending.state.pendingCheckpoint != null) {
                    String hash = pending.state.pendingCheckpoint.payload.textHash;
                    event.checkpoints.put(hash, pending.state.pendingCheckpoint); pendingTargets.add(hash);
                }
                events.put(event.id, event);
            }
            var result = materialize(fileId, rows.getFirst().path, topological(events), snapshots).state;
            boolean restricted = restrictOrphanEvidence(result, orphanEvidence);
            if (!recovered && !restricted) return null;
            if (pendingTargets.size() == 1) result.pendingCheckpoint = copy(result.checkpoints.get(pendingTargets.first()), ReviewHistoryStore.CheckpointState.class);
            return result;
        } catch (SQLException failure) { throw problem("Cannot load pending review provenance", failure); }
    }

    private static boolean restrictOrphanEvidence(ReviewHistoryStore.FileState file, Set<String> orphanEvidence) {
        if (orphanEvidence.isEmpty()) return false;
        ReviewHistory history = ReviewJournalCodec.read(file);
        var index = ProjectionCheckpointCodec.index(history);
        var refs = index.rows().keySet().stream().filter(ref -> orphanEvidence.contains(ref.evidenceId()))
                .sorted(Comparator.comparing(dev.doreview.core.EvidenceRef::evidenceId).thenComparingInt(dev.doreview.core.EvidenceRef::baselineStart)
                        .thenComparingInt(dev.doreview.core.EvidenceRef::baselineEnd)).toList();
        if (refs.isEmpty()) return false;
        if (file.checkpoints.isEmpty()) {
            FileSnapshot source = history.snapshots().values().iterator().next();
            var empty = new ReviewHistoryStore.CheckpointState(); empty.payload.version = 2;
            empty.payload.textHash = source.id(); empty.payload.textLength = source.text().length();
            file.checkpoints.put(source.id(), empty);
        }
        for (var checkpoint : file.checkpoints.values()) {
            checkpoint.payload.version = 2;
            var byRef = new HashMap<dev.doreview.core.EvidenceRef, ReviewHistoryStore.CheckpointRefState>();
            for (var stored : checkpoint.payload.refs) byRef.put(new dev.doreview.core.EvidenceRef(stored.evidenceId, stored.start, stored.end), stored);
            for (var ref : refs) {
                var stored = byRef.get(ref);
                if (stored == null) {
                    stored = new ReviewHistoryStore.CheckpointRefState(); stored.evidenceId = ref.evidenceId();
                    stored.start = ref.baselineStart(); stored.end = ref.baselineEnd(); checkpoint.payload.refs.add(stored);
                }
                stored.authoritative = true; stored.allowRematch = false; stored.live = null;
                stored.deleted = false; stored.anchor = null; stored.unresolved = null;
            }
            checkpoint.checksum = ProjectionCheckpointCodec.checksum(checkpoint.payload);
            ProjectionCheckpointCodec.validate(index, checkpoint);
        }
        return true;
    }

    private record PendingRow(String writer, String path, String payload, Author author) { }
    private List<PendingRow> pendingRows(String fileId, String token) throws SQLException {
        var result = new ArrayList<PendingRow>();
        try (var statement = connection.prepareStatement("SELECT writer_id,path,payload,author FROM pending WHERE file_id=? AND checkout_token=? ORDER BY writer_id")) {
            statement.setString(1, fileId); statement.setString(2, token);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Author author = JSON.fromJson(rows.getString(4), Author.class);
                    if (author == null) throw new IllegalArgumentException("Missing pending author");
                    result.add(new PendingRow(rows.getString(1), ReviewRepositoryPaths.validateRelativePath(rows.getString(2)), rows.getString(3), author));
                }
            }
        }
        return result;
    }

    public synchronized Map<String, String> pendingFiles(String token) {
        requireOpen();
        var result = new TreeMap<String, String>();
        try (var statement = connection.prepareStatement("SELECT file_id,path FROM pending WHERE checkout_token=?")) {
            statement.setString(1, token);
            try (var rows = statement.executeQuery()) { while (rows.next()) result.put(rows.getString(1), rows.getString(2)); }
            return Map.copyOf(result);
        } catch (SQLException failure) { throw problem("Cannot list pending provenance", failure); }
    }

    public synchronized void flushAllPending(String token) {
        requireOpen();
        for (String fileId : pendingFiles(token).keySet()) flushPending(fileId, token);
    }

    /** Startup recovery publishes applicable work while retaining reset/rebase orphans. */
    public synchronized int flushRecoverablePending(String token) {
        requireOpen();
        int retained = 0;
        for (String fileId : pendingFiles(token).keySet()) retained += flushPendingRows(fileId, token, true).retained;
        return retained;
    }

    public synchronized boolean hasPendingOtherCheckouts(String token) {
        requireOpen();
        try (var statement = connection.prepareStatement("SELECT 1 FROM pending WHERE checkout_token<>? LIMIT 1")) {
            statement.setString(1, token); try (var rows = statement.executeQuery()) { return rows.next(); }
        } catch (SQLException failure) { throw problem("Cannot inspect retained pending provenance", failure); }
    }

    /** Publishes durable current-checkout work before rebuilding disposable indexes. */
    public synchronized Path rebuildIndex() {
        requireOpen(); rescan();
        String token = checkoutToken;
        flushAllPending(token);
        if (hasPendingOtherCheckouts(token)) throw new IllegalStateException("Retained provenance from another checkout must be published before rebuilding the index");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1 FROM pending LIMIT 1")) {
            if (rows.next()) throw new IllegalStateException("Pending provenance must be published before rebuilding the index");
        } catch (SQLException failure) { throw problem("Cannot inspect pending provenance", failure); }
        Path destination = database.resolveSibling("history-before-rebuild-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".sqlite3");
        backup(destination);
        try {
            transaction(() -> {
                for (String table : List.of("dependencies", "evidence", "revocations", "checkpoints", "aliases", "event_snapshots", "snapshots", "events")) execute("DELETE FROM " + table);
            });
            scanned.clear(); cache.clear(); eventToken = ""; checkoutToken = "";
            rescan();
            return destination;
        } catch (Exception failure) { throw problem("Cannot rebuild review index; consistent backup was preserved", failure); }
    }

    public synchronized List<Version> versions(String fileId) {
        requireOpen();
        var result = new LinkedHashMap<String, Version>();
        try (var statement = connection.prepareStatement("SELECT e.payload,v.author FROM evidence e JOIN events v ON e.event_id=v.id WHERE e.file_id=? AND v.active=1 ORDER BY v.created_at,e.event_id")) {
            statement.setString(1, fileId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var proof = JSON.fromJson(rows.getString(1), ReviewHistoryStore.EvidenceState.class);
                    result.put(proof.snapshotId, new Version(proof.snapshotId, proof.createdAt, JSON.fromJson(rows.getString(2), Author.class)));
                }
            }
            return List.copyOf(result.values());
        } catch (SQLException failure) { throw problem("Cannot read review versions", failure); }
    }

    public synchronized String snapshot(String hash) {
        requireOpen(); validateHash(hash);
        try (var statement = connection.prepareStatement("SELECT encoding,data FROM snapshots WHERE hash=?")) {
            statement.setString(1, hash);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                if (rows.getInt(1) != 1) throw new UnsupportedSnapshotEncoding(rows.getInt(1));
                String text = gunzip(rows.getBytes(2));
                new FileSnapshot(hash, text);
                return text;
            }
        } catch (Exception failure) { throw problem("Cannot load review snapshot", failure); }
    }

    /** SQLite's backup API includes committed WAL contents and creates a consistent snapshot. */
    public synchronized void backup(Path destination) {
        requireOpen();
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            if (Files.exists(destination)) throw new IllegalArgumentException("Backup destination already exists");
            int code = connection.unwrap(SQLiteConnection.class).getDatabase().backup("main", destination.toAbsolutePath().toString(), null);
            if (code != 0) throw new SQLException("SQLite backup failed: " + code);
        } catch (Exception failure) { throw problem("Cannot back up review database", failure); }
    }

    private LoadedFile materialize(String fileId, String path, List<Event> events) throws SQLException {
        return materialize(fileId, path, events, Map.of());
    }

    private LoadedFile materialize(String fileId, String path, List<Event> events, Map<String, String> pendingSnapshots) throws SQLException {
        var result = new ReviewHistoryStore.FileState(); result.path = path;
        var graph = new HashMap<String, Event>(); events.forEach(event -> graph.put(event.id, event));
        var aliases = new TreeSet<String>();
        var authors = new TreeMap<String, Author>();
        var evidence = new LinkedHashMap<String, ReviewHistoryStore.EvidenceState>();
        var origins = new HashMap<String, Set<String>>();
        var revocations = new LinkedHashMap<String, ReviewHistoryStore.RevocationState>();
        var candidates = new TreeMap<String, List<CheckpointEvent>>();
        long revision = 0;
        for (Event event : events) {
            aliases.add(event.path); aliases.addAll(event.aliases); revision++;
            for (var proof : event.evidence) {
                var old = evidence.get(proof.id);
                if (old != null && !canonicalEvidence(old).equals(canonicalEvidence(proof))) throw new IllegalStateException("Conflicting review evidence " + proof.id);
                origins.computeIfAbsent(proof.id, ignored -> new HashSet<>()).add(event.id);
                if (old == null) {
                    var value = copy(proof, ReviewHistoryStore.EvidenceState.class); value.sequence = revision;
                    evidence.put(value.id, value); authors.put(value.id, new Author(event.authorName, event.authorEmail));
                    if (!result.snapshots.containsKey(value.snapshotId)) {
                        String text = pendingSnapshots.containsKey(value.snapshotId) ? pendingSnapshots.get(value.snapshotId) : snapshot(value.snapshotId);
                        if (text == null) throw new IllegalStateException("Missing review snapshot");
                        result.snapshots.put(value.snapshotId, text);
                    }
                }
            }
            for (var revoked : event.revocations) {
                if (!evidence.containsKey(revoked.evidenceId)) throw new IllegalStateException("Revocation without a causal review");
                if (origins.get(revoked.evidenceId).stream().noneMatch(origin -> event.id.equals(origin) || ancestor(origin, event.id, graph)))
                    throw new IllegalStateException("Revocation does not observe its review evidence");
                var value = copy(revoked, ReviewHistoryStore.RevocationState.class); value.sequence = revision;
                revocations.putIfAbsent(canonicalRevocation(value), value);
            }
            for (var checkpoint : event.checkpoints.entrySet()) {
                var list = candidates.computeIfAbsent(checkpoint.getKey(), ignored -> new ArrayList<>());
                list.removeIf(old -> ancestor(old.event.id, event.id, graph));
                list.add(new CheckpointEvent(event, checkpoint.getValue()));
            }
        }
        result.evidence.addAll(evidence.values()); result.revocations.addAll(revocations.values()); result.revision = revision;
        ReviewHistory history = ReviewJournalCodec.read(result);
        for (var entry : candidates.entrySet()) {
            var values = entry.getValue();
            var unique = new LinkedHashMap<String, ReviewHistoryStore.CheckpointState>();
            for (var candidate : values) {
                ProjectionCheckpointCodec.validate(ProjectionCheckpointCodec.index(history), candidate.checkpoint);
                unique.put(JSON.toJson(candidate.checkpoint), candidate.checkpoint);
            }
            if (unique.size() == 1) result.checkpoints.put(entry.getKey(), copy(unique.values().iterator().next(), ReviewHistoryStore.CheckpointState.class));
            else {
                // No last-writer-wins for concurrent provenance. Keep all evidence restricted.
                int length = unique.values().iterator().next().payload.textLength;
                if (unique.values().stream().anyMatch(checkpoint -> checkpoint.payload.textLength != length)) throw new IllegalStateException("Conflicting checkpoint snapshot length");
                var conservative = new ReviewHistoryStore.CheckpointState();
                conservative.payload.version = 2; conservative.payload.textHash = entry.getKey(); conservative.payload.textLength = length;
                var refs = new ArrayList<>(ProjectionCheckpointCodec.index(history).rows().keySet());
                refs.sort(Comparator.comparing(dev.doreview.core.EvidenceRef::evidenceId).thenComparingInt(dev.doreview.core.EvidenceRef::baselineStart));
                for (var ref : refs) {
                    var stored = new ReviewHistoryStore.CheckpointRefState(); stored.evidenceId = ref.evidenceId();
                    stored.start = ref.baselineStart(); stored.end = ref.baselineEnd(); stored.authoritative = true;
                    conservative.payload.refs.add(stored);
                }
                conservative.checksum = ProjectionCheckpointCodec.checksum(conservative.payload);
                ProjectionCheckpointCodec.validate(ProjectionCheckpointCodec.index(history), conservative);
                result.checkpoints.put(entry.getKey(), conservative);
            }
        }
        var frontier = new TreeSet<String>(); events.forEach(event -> frontier.add(event.id));
        events.forEach(event -> frontier.removeAll(event.parents));
        return new LoadedFile(fileId, result, Set.copyOf(aliases), Map.copyOf(authors), Set.copyOf(frontier));
    }

    private record CheckpointEvent(Event event, ReviewHistoryStore.CheckpointState checkpoint) { }

    private static boolean ancestor(String candidate, String id, Map<String, Event> byId) {
        var queue = new ArrayList<String>(); queue.add(id); var seen = new HashSet<String>();
        for (int i = 0; i < queue.size(); i++) {
            String current = queue.get(i); if (!seen.add(current)) continue;
            Event event = byId.get(current); if (event == null) continue;
            if (event.parents.contains(candidate)) return true;
            queue.addAll(event.parents);
        }
        return false;
    }

    private PublicationFence publicationFence(Event event) throws IOException, SQLException {
        if (dataVersion() != externalDataVersion) return null;
        var ownFiles = new HashSet<Path>(); ownFiles.add(eventPath(event.id)); ownFiles.add(shared.resolve(".gitattributes"));
        for (String hash : event.snapshots) ownFiles.add(snapshotPath(hash));
        var touched = new HashSet<Path>();
        for (Path file : ownFiles) for (Path directory = file.getParent(); directory != null && directory.startsWith(shared); directory = directory.getParent()) touched.add(directory);
        var directories = new HashSet<>(touched);
        for (Path file : scanned.keySet()) directories.add(file.getParent());
        var stamps = new HashMap<Path, DirectoryStamp>();
        var children = new HashMap<Path, Set<String>>();
        for (Path directory : directories) {
            DirectoryStamp stamp = directoryStamp(directory); stamps.put(directory, stamp);
            if (touched.contains(directory)) children.put(directory, directoryChildren(directory));
        }
        // Only the exact files/directories created by this publication may alter membership.
        for (Path file : ownFiles) {
            children.computeIfAbsent(file.getParent(), ignored -> new HashSet<>()).add(file.getFileName().toString());
            for (Path directory = file.getParent(); directory != null && !directory.equals(shared); directory = directory.getParent())
                children.computeIfAbsent(directory.getParent(), ignored -> new HashSet<>()).add(directory.getFileName().toString());
        }
        var inputs = new HashMap<Path, Fingerprint>();
        try (var statement = connection.prepareStatement("SELECT id FROM events WHERE file_id=? AND active=1")) {
            statement.setString(1, event.fileId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Path path = eventPath(rows.getString(1)); Fingerprint value = scanned.get(path);
                    if (value == null) return null;
                    inputs.put(path, value);
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT DISTINCT s.hash FROM event_snapshots s JOIN events e ON e.id=s.event_id WHERE e.file_id=? AND e.active=1")) {
            statement.setString(1, event.fileId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Path path = snapshotPath(rows.getString(1)); Fingerprint value = scanned.get(path);
                    if (value == null) return null;
                    inputs.put(path, value);
                }
            }
        }
        CheckoutState checkout = readCheckoutState();
        if (!checkout.token.equals(checkoutToken) || !checkout.fingerprint.equals(gitFingerprint)) return null;
        return new PublicationFence(externalDataVersion, checkout, stamps, children, inputs, ownFiles);
    }

    private boolean publicationUnchanged(PublicationFence fence) throws IOException, SQLException {
        if (fence == null || dataVersion() != fence.databaseVersion || !readCheckoutState().equals(fence.checkout)) return false;
        for (var entry : fence.directories.entrySet()) {
            if (fence.expectedChildren.containsKey(entry.getKey())) {
                if (!directoryChildren(entry.getKey()).equals(fence.expectedChildren.get(entry.getKey()))) return false;
            } else if (!directoryStamp(entry.getKey()).equals(entry.getValue())) return false;
        }
        for (var entry : fence.currentFileInputs.entrySet()) {
            int limit = entry.getKey().toString().endsWith(".json") ? MAX_EVENT_BYTES : MAX_SNAPSHOT_BYTES;
            safeSharedPath(entry.getKey());
            if (!fingerprint(entry.getKey(), limit, entry.getValue().digest).equals(entry.getValue())) return false;
        }
        return true;
    }

    /** Fast path for our own verified files; external membership/DB/Git changes trigger rescan. */
    private boolean indexOwnPublication(Event event, String payload, PublicationFence fence) throws IOException, SQLException {
        if (!publicationUnchanged(fence)) return false;
        long started = System.nanoTime(); decodedEventFiles = 1; readSnapshotFiles = 0; indexedEvents = 0;
        var verifiedSnapshots = new HashMap<Path, Fingerprint>();
        for (String hash : event.snapshots) {
            Path path = snapshotPath(hash);
            Fingerprint before = fingerprint(path, MAX_SNAPSHOT_BYTES, hash), known = scanned.get(path);
            if (known == null) {
                readSnapshotFiles++; new FileSnapshot(hash, Files.readString(path, StandardCharsets.UTF_8));
                if (!before.equals(fingerprint(path, MAX_SNAPSHOT_BYTES, hash))) return false;
                verifiedSnapshots.put(path, before);
            } else {
                if (!before.equals(known)) return false;
                verifiedSnapshots.put(path, known);
            }
        }
        final boolean[] indexed = {false};
        transaction(() -> {
            if (dataVersion() != fence.databaseVersion) return;
            for (String parent : event.parents) if (!active(parent)) throw new IllegalStateException("Observed review frontier is absent from current checkout");
            String old = indexedDigest(event.id);
            if (old == null) index(event, payload);
            else if (!old.equals(ReviewRepositoryPaths.sha256(payload))) throw new IllegalStateException("Conflicting immutable review event " + event.id);
            try (var update = connection.prepareStatement("UPDATE events SET active=1 WHERE id=?")) { update.setString(1, event.id); update.executeUpdate(); }
            indexed[0] = true;
        });
        if (!indexed[0] || !publicationUnchanged(fence)) return false;
        // Recheck our event bytes as well; unlike snapshots, the event may have been indexed
        // before a concurrent external tool touched it after its atomic publication.
        Path eventFile = eventPath(event.id);
        Fingerprint eventFingerprint = fingerprint(eventFile, MAX_EVENT_BYTES, ReviewRepositoryPaths.sha256(payload));
        String actual = FileSnapshot.normalize(Files.readString(eventFile, StandardCharsets.UTF_8));
        if (!actual.equals(payload) || !eventFingerprint.equals(fingerprint(eventFile, MAX_EVENT_BYTES, eventFingerprint.digest))) return false;
        for (var entry : verifiedSnapshots.entrySet()) if (!entry.getValue().equals(fingerprint(entry.getKey(), MAX_SNAPSHOT_BYTES, entry.getValue().digest))) return false;
        scanned.put(eventFile, eventFingerprint); scanned.putAll(verifiedSnapshots);
        var activeIds = new TreeSet<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM events WHERE active=1")) {
            while (rows.next()) activeIds.add(rows.getString(1));
        }
        eventToken = ReviewRepositoryPaths.sha256(String.join("\n", activeIds));
        checkoutToken = fence.checkout.token; gitFingerprint = fence.checkout.fingerprint;
        externalDataVersion = dataVersion(); cache.clear(); lastScanNanos = System.nanoTime() - started;
        return true;
    }

    private DirectoryStamp directoryStamp(Path directory) throws IOException {
        safeSharedPath(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new DirectoryStamp(false, 0, "");
        var attributes = Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) throw new IOException("Invalid shared history directory");
        return new DirectoryStamp(true, attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), String.valueOf(attributes.fileKey()));
    }

    private Set<String> directoryChildren(Path directory) throws IOException {
        DirectoryStamp stamp = directoryStamp(directory);
        var children = new HashSet<String>();
        if (stamp.exists) try (var paths = Files.list(directory)) { paths.forEach(path -> children.add(path.getFileName().toString())); }
        return children;
    }

    private void index(Event event, String payload) throws SQLException, IOException {
        indexedEvents++;
        for (String hash : event.snapshots) {
            boolean stored;
            try (var query = connection.prepareStatement("SELECT 1 FROM snapshots WHERE hash=?")) {
                query.setString(1, hash); try (var rows = query.executeQuery()) { stored = rows.next(); }
            }
            if (!stored) {
                Path path = snapshotPath(hash); safeSharedPath(path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_SNAPSHOT_BYTES) throw new IllegalStateException("Missing or invalid review snapshot " + hash);
                readSnapshotFiles++; String text = Files.readString(path, StandardCharsets.UTF_8); new FileSnapshot(hash, text);
                try (var insert = connection.prepareStatement("INSERT INTO snapshots(hash,encoding,data) VALUES(?,1,?)")) { insert.setString(1, hash); insert.setBytes(2, gzip(text)); insert.executeUpdate(); }
            }
            try (var insert = connection.prepareStatement("INSERT INTO event_snapshots VALUES(?,?)")) { insert.setString(1, event.id); insert.setString(2, hash); insert.executeUpdate(); }
        }
        try (var insert = connection.prepareStatement("INSERT INTO events(id,digest,payload,active,file_id,path,author,created_at) VALUES(?,?,?,0,?,?,?,?)")) {
            insert.setString(1, event.id); insert.setString(2, ReviewRepositoryPaths.sha256(payload)); insert.setString(3, payload);
            insert.setString(4, event.fileId); insert.setString(5, event.path); insert.setString(6, JSON.toJson(new Author(event.authorName, event.authorEmail))); insert.setLong(7, event.createdAt); insert.executeUpdate();
        }
        for (String parent : event.parents) try (var insert = connection.prepareStatement("INSERT INTO dependencies VALUES(?,?)")) { insert.setString(1, event.id); insert.setString(2, parent); insert.executeUpdate(); }
        for (var proof : event.evidence) try (var insert = connection.prepareStatement("INSERT INTO evidence VALUES(?,?,?,?)")) { insert.setString(1, event.fileId); insert.setString(2, proof.id); insert.setString(3, event.id); insert.setString(4, JSON.toJson(proof)); insert.executeUpdate(); }
        for (int i = 0; i < event.revocations.size(); i++) try (var insert = connection.prepareStatement("INSERT INTO revocations VALUES(?,?,?,?)")) { insert.setString(1, event.fileId); insert.setString(2, event.id); insert.setInt(3, i); insert.setString(4, JSON.toJson(event.revocations.get(i))); insert.executeUpdate(); }
        for (var checkpoint : event.checkpoints.entrySet()) try (var insert = connection.prepareStatement("INSERT INTO checkpoints VALUES(?,?,?,?)")) { insert.setString(1, event.fileId); insert.setString(2, event.id); insert.setString(3, checkpoint.getKey()); insert.setString(4, JSON.toJson(checkpoint.getValue())); insert.executeUpdate(); }
        var paths = new TreeSet<>(event.aliases); paths.add(event.path);
        for (String path : paths) try (var insert = connection.prepareStatement("INSERT INTO aliases VALUES(?,?,?)")) { insert.setString(1, event.fileId); insert.setString(2, path); insert.setString(3, event.id); insert.executeUpdate(); }
    }

    private String indexedPayload(String id, String digest) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT payload,digest FROM events WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) { return rows.next() && (digest == null || digest.equals(rows.getString(2))) ? rows.getString(1) : null; }
        }
    }
    private String indexedDigest(String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT digest FROM events WHERE id=?")) {
            statement.setString(1, id); try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private boolean active(String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT active FROM events WHERE id=?")) {
            statement.setString(1, id); try (var rows = statement.executeQuery()) { return rows.next() && rows.getInt(1) == 1; }
        }
    }

    private List<String> tips(String fileId) throws SQLException {
        var result = new TreeSet<String>();
        try (var statement = connection.prepareStatement("SELECT e.id FROM events e WHERE e.file_id=? AND e.active=1 AND NOT EXISTS (SELECT 1 FROM dependencies d JOIN events child ON child.id=d.event_id WHERE d.parent_id=e.id AND child.active=1)")) {
            statement.setString(1, fileId);
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return new ArrayList<>(result);
    }

    private static List<Event> topological(Map<String, Event> events) {
        for (Event event : events.values()) for (String parent : event.parents) {
            if (!events.containsKey(parent)) throw new IllegalStateException("Missing causal dependency " + parent);
            if (!events.get(parent).fileId.equals(event.fileId)) throw new IllegalStateException("Cross-file causal dependency");
        }
        return topologicalSubset(events);
    }

    private static List<Event> topologicalSubset(Map<String, Event> events) {
        var degrees = new HashMap<String, Integer>(); var children = new HashMap<String, List<String>>();
        for (Event event : events.values()) {
            int degree = 0;
            for (String parent : event.parents) if (events.containsKey(parent)) { degree++; children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(event.id); }
            degrees.put(event.id, degree);
        }
        var ready = new TreeSet<String>(); degrees.forEach((id, count) -> { if (count == 0) ready.add(id); });
        var ordered = new ArrayList<Event>();
        while (!ready.isEmpty()) {
            String id = ready.pollFirst(); ordered.add(events.get(id));
            for (String child : children.getOrDefault(id, List.of())) if (degrees.compute(child, (ignored, n) -> n - 1) == 0) ready.add(child);
        }
        if (ordered.size() != events.size()) throw new IllegalStateException("Cyclic review history dependencies");
        return ordered;
    }

    private static Event parseEvent(String payload) {
        try {
            Event event = JSON.fromJson(payload, Event.class);
            if (event == null || event.version != 1) throw new IllegalArgumentException("Unsupported review event version");
            UUID.fromString(event.id); validateIdentifier(event.fileId); ReviewRepositoryPaths.validateRelativePath(event.path);
            new Author(event.authorName, event.authorEmail);
            if (event.createdAt < 0 || event.authorName == null || event.authorName.isBlank() || event.parents == null || event.aliases == null
                    || event.evidence == null || event.revocations == null || event.checkpoints == null || event.snapshots == null) throw new IllegalArgumentException("Incomplete review event");
            if (new HashSet<>(event.parents).size() != event.parents.size() || event.parents.contains(event.id)) throw new IllegalArgumentException("Invalid review parents");
            event.parents.forEach(UUID::fromString); event.aliases.forEach(ReviewRepositoryPaths::validateRelativePath); event.snapshots.forEach(SharedHistoryRepository::validateHash);
            for (var proof : event.evidence) {
                validateIdentifier(proof.id); validateHash(proof.snapshotId);
                if (!event.snapshots.contains(proof.snapshotId) || proof.createdAt < 0 || proof.ranges == null || proof.ranges.isEmpty()) throw new IllegalArgumentException("Invalid review evidence");
                proof.ranges.forEach(range -> new ReviewedRange(range.start, range.end));
            }
            for (var revoked : event.revocations) {
                validateIdentifier(revoked.evidenceId);
                if (revoked.ranges == null || revoked.ranges.isEmpty()) throw new IllegalArgumentException("Invalid review revocation");
                revoked.ranges.forEach(range -> new ReviewedRange(range.start, range.end));
            }
            event.checkpoints.forEach((hash, checkpoint) -> {
                validateHash(hash);
                if (checkpoint == null || checkpoint.payload == null || !hash.equals(checkpoint.payload.textHash)) throw new IllegalArgumentException("Invalid checkpoint key");
            });
            return event;
        } catch (JsonParseException | NullPointerException failure) { throw new IllegalArgumentException("Invalid review event JSON", failure); }
    }

    private static void validateFile(ReviewHistoryStore.FileState state) {
        ReviewHistory history = ReviewJournalCodec.read(state);
        var index = ProjectionCheckpointCodec.index(history);
        if (state.checkpoints == null) throw new IllegalArgumentException("Missing checkpoint collection");
        state.checkpoints.forEach((hash, checkpoint) -> {
            if (checkpoint == null || checkpoint.payload == null || !hash.equals(checkpoint.payload.textHash)) throw new IllegalArgumentException("Checkpoint key mismatch");
            ProjectionCheckpointCodec.validate(index, checkpoint);
        });
        if (state.pendingCheckpoint != null) ProjectionCheckpointCodec.validate(index, state.pendingCheckpoint);
    }
    private static void validatePendingHeader(PendingState pending) {
        if (pending == null || (pending.version != 1 && pending.version != 2) || pending.state == null || pending.parents == null || pending.createdAt < 0)
            throw new IllegalArgumentException("Incomplete or unsupported pending provenance");
        pending.parents.forEach(UUID::fromString);
    }

    private void ensureAttributes() throws IOException {
        Path attributes = shared.resolve(".gitattributes");
        if (!Files.exists(attributes)) writeImmutable(attributes, "v1/events/** text eol=lf\nv1/snapshots/** -text\n".getBytes(StandardCharsets.UTF_8));
    }
    private Path eventPath(String id) { UUID.fromString(id); return shared.resolve("v1/events").resolve(id.substring(0, 2)).resolve(id + ".json"); }
    private Path snapshotPath(String hash) { validateHash(hash); return shared.resolve("v1/snapshots").resolve(hash.substring(0, 2)).resolve(hash + ".txt"); }

    private void safeSharedPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(shared)) throw new IllegalArgumentException("Review path escapes shared directory");
        for (Path candidate = absolute; candidate != null && candidate.startsWith(shared); candidate = candidate.getParent())
            if (Files.isSymbolicLink(candidate)) throw new IOException("Symbolic links are not supported inside shared history");
    }

    private void safeSharedPath(Path path, Set<Path> checkedDirectories) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(shared)) throw new IllegalArgumentException("Review path escapes shared directory");
        // fingerprint() rejects symlink files. Directory checks are shared within this scan;
        // checking every ancestor again for every event is expensive on Windows filesystems.
        for (Path candidate = absolute.getParent(); candidate != null && candidate.startsWith(shared); candidate = candidate.getParent()) {
            if (!checkedDirectories.add(candidate)) break;
            if (Files.isSymbolicLink(candidate)) throw new IOException("Symbolic links are not supported inside shared history");
        }
    }

    private void writeImmutable(Path target, byte[] bytes) throws IOException {
        safeSharedPath(target); Files.createDirectories(target.getParent()); safeSharedPath(target);
        if (Files.exists(target)) {
            if (!java.util.Arrays.equals(bytes, Files.readAllBytes(target))) throw new IOException("Conflicting immutable review file " + target.getFileName());
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
            catch (java.nio.file.FileAlreadyExistsException raced) {
                if (!java.util.Arrays.equals(bytes, Files.readAllBytes(target))) throw raced;
            }
            // POSIX filesystems require the directory entry to be synchronized after rename.
            // Windows does not expose directory fsync through FileChannel.
            try (var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (UnsupportedOperationException unavailable) { }
            catch (IOException unavailable) {
                if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) throw unavailable;
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] gzip(String text) throws IOException {
        byte[] source = text.getBytes(StandardCharsets.UTF_8);
        if (source.length > MAX_SNAPSHOT_BYTES) throw new IOException("Review snapshot exceeds size limit");
        var buffer = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(buffer)) { output.write(source); }
        return buffer.toByteArray();
    }
    private static String gunzip(byte[] bytes) throws IOException {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] unpacked = input.readNBytes(MAX_SNAPSHOT_BYTES + 1);
            if (unpacked.length > MAX_SNAPSHOT_BYTES) throw new IOException("Review snapshot exceeds size limit");
            return new String(unpacked, StandardCharsets.UTF_8);
        }
    }
    private static Fingerprint fingerprint(Path path, int limit, String digest) throws IOException {
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > limit) throw new IOException("Missing, oversized or invalid review file " + path.getFileName());
        return new Fingerprint(attributes.size(), attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), digest);
    }
    private record CheckoutState(String token, String fingerprint) { }
    private CheckoutState readCheckoutState() throws IOException {
        Path marker = root.resolve(".git");
        if (!Files.exists(marker)) {
            String token = ReviewRepositoryPaths.sha256("directory\n" + root);
            return new CheckoutState(token, token);
        }
        Path git = marker;
        if (Files.isRegularFile(marker)) {
            String link = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (!link.startsWith("gitdir:")) throw new IllegalStateException("Invalid Git worktree metadata");
            git = root.resolve(link.substring(7).trim()).normalize().toAbsolutePath();
        }
        String head = Files.readString(git.resolve("HEAD"), StandardCharsets.UTF_8).trim();
        String commit = head;
        if (head.startsWith("ref:")) {
            String reference = head.substring(4).trim();
            if (!reference.startsWith("refs/") || reference.contains("..") || reference.indexOf('\\') >= 0) throw new IllegalStateException("Invalid Git HEAD reference");
            Path common = git;
            if (Files.isRegularFile(git.resolve("commondir"))) common = git.resolve(Files.readString(git.resolve("commondir")).trim()).normalize();
            Path ref = common.resolve(reference);
            if (Files.isRegularFile(ref)) commit = Files.readString(ref).trim();
            else if (Files.isRegularFile(common.resolve("packed-refs"))) {
                for (String line : Files.readAllLines(common.resolve("packed-refs"))) if (line.endsWith(" " + reference)) { commit = line.substring(0, line.indexOf(' ')); break; }
            }
        }
        String token = ReviewRepositoryPaths.sha256("git\n" + root + "\n" + head);
        String fingerprint = ReviewRepositoryPaths.sha256("git\n" + root + "\n" + head + "\n" + commit);
        return new CheckoutState(token, fingerprint);
    }
    private static void validateHash(String hash) { if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid snapshot hash"); }
    static boolean supportedSqliteVersion(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] parts = version.split("\\.");
        int[] minimum = {3, 51, 3};
        for (int i = 0; i < 3; i++) {
            long actual;
            try { actual = Long.parseLong(parts[i]); } catch (NumberFormatException invalid) { return false; }
            if (actual != minimum[i]) return actual > minimum[i];
        }
        return true;
    }
    private static void validateIdentifier(String id) { if (id == null || !id.matches("[A-Za-z0-9._-]{1,160}")) throw new IllegalArgumentException("Invalid review identifier"); }
    private static String canonicalEvidence(ReviewHistoryStore.EvidenceState state) { var value = copy(state, ReviewHistoryStore.EvidenceState.class); value.sequence = 0; return JSON.toJson(value); }
    private static String canonicalRevocation(ReviewHistoryStore.RevocationState state) { var value = copy(state, ReviewHistoryStore.RevocationState.class); value.sequence = 0; return JSON.toJson(value); }
    static ReviewHistoryStore.FileState copyFile(ReviewHistoryStore.FileState file) {
        var result = new ReviewHistoryStore.FileState();
        result.path = file.path; result.revision = file.revision; result.snapshots = new TreeMap<>(file.snapshots);
        for (var proof : file.evidence) result.evidence.add(copy(proof, ReviewHistoryStore.EvidenceState.class));
        for (var revoked : file.revocations) result.revocations.add(copy(revoked, ReviewHistoryStore.RevocationState.class));
        file.checkpoints.forEach((hash, checkpoint) -> result.checkpoints.put(hash, copy(checkpoint, ReviewHistoryStore.CheckpointState.class)));
        result.pendingCheckpoint = copy(file.pendingCheckpoint, ReviewHistoryStore.CheckpointState.class);
        return result;
    }
    private static <T> T copy(T value, Class<T> type) { return JSON.fromJson(JSON.toJson(value), type); }
    private static LoadedFile copyLoaded(LoadedFile loaded, String path) { var state = copyFile(loaded.state); state.path = path; return new LoadedFile(loaded.fileId, state, loaded.aliases, loaded.evidenceAuthors, loaded.frontier); }

    private long dataVersion() throws SQLException { try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA data_version")) { rows.next(); return rows.getLong(1); } }
    private void invalidateExternal() { try { if (dataVersion() != externalDataVersion) rescan(); } catch (SQLException failure) { throw problem("Cannot inspect review database", failure); } }
    private void execute(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    @FunctionalInterface private interface SqlWork { void run() throws Exception; }
    private void transaction(SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try { work.run(); connection.commit(); }
        catch (Exception failure) { connection.rollback(); if (failure instanceof SQLException sql) throw sql; throw problem("Review transaction failed", failure); }
        finally { connection.setAutoCommit(true); }
    }
    private void requireOpen() { backgroundOnly(); if (closed) throw new IllegalStateException("Review repository is closed"); }
    private static void backgroundOnly() {
        var application = ApplicationManager.getApplication();
        if (application != null && !application.isUnitTestMode() && application.isDispatchThread())
            throw new IllegalStateException("Review history I/O must run outside the EDT");
    }
    private static IllegalStateException problem(String message, Throwable cause) { return new IllegalStateException(message + ": " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause); }

    @Override public synchronized void close() {
        backgroundOnly(); if (closed) return; closed = true; cache.clear(); scanned.clear();
        try { connection.close(); } catch (SQLException failure) { throw problem("Cannot close review database", failure); }
    }
}
