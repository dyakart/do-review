package dev.doreview.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import dev.doreview.core.EvidenceRef;
import dev.doreview.core.FileSnapshot;
import dev.doreview.core.ProjectionHints;
import dev.doreview.core.ReviewEvidence;
import dev.doreview.core.ReviewHistory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** Background persistence and bounded, prevalidated per-file editor working sets. */
final class SharedReviewHistoryStore implements Disposable {
    private static final Logger LOG = Logger.getInstance(SharedReviewHistoryStore.class);
    private static final int MAX_IDLE_FILES = 16;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "DoReview history storage"); thread.setDaemon(true); return thread;
    });
    private final Map<Key, View> views = new ConcurrentHashMap<>();
    private final Map<SessionKey, Session> sessions = new HashMap<>(); // worker confined
    private final Map<Project, Set<Path>> projectRoots = new HashMap<>(); // worker confined
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "DoReview checkout watcher"); thread.setDaemon(true); return thread;
    });
    private final Set<Project> initialized = ConcurrentHashMap.newKeySet();
    private final Map<Project, List<String>> warnings = new ConcurrentHashMap<>();
    private volatile boolean disposed;
    private final java.util.concurrent.CompletableFuture<Void> closed = new java.util.concurrent.CompletableFuture<>();
    java.util.concurrent.CompletableFuture<Void> closed() { return closed; }
    java.util.concurrent.CompletableFuture<Void> idle() {
        if (disposed) return closed;
        var future = new java.util.concurrent.CompletableFuture<Void>();
        worker.execute(() -> future.complete(null)); return future;
    }

    SharedReviewHistoryStore() {
        watcher.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
    }

    private record Key(Project project, String path) { }
    private record SessionKey(Project project, Path root) { }
    private static final class Session {
        final SharedHistoryRepository repository;
        final SharedHistoryRepository.Author author;
        Session(Path root) {
            repository = new SharedHistoryRepository(root, ReviewRepositoryPaths.databasePath(root));
            author = new SharedHistoryRepository.Author(gitConfig(root, "user.name", System.getProperty("user.name", "Unknown author")),
                    gitConfig(root, "user.email", ""));
        }
    }
    private static final class View {
        final Key key;
        final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        volatile LegacyReviewHistoryModel model;
        volatile Session session;
        volatile String repoPath, fileId, token, requestedText = "";
        volatile SharedHistoryRepository.LoadedFile persisted;
        volatile Map<String, SharedHistoryRepository.Author> authors = Map.of();
        volatile Set<String> observedFrontier = Set.of();
        final Set<String> localPublications = new HashSet<>(); // storage worker only
        final Map<String, String> checkpointTexts = new ConcurrentHashMap<>();
        volatile long generation, pendingGeneration;
        volatile boolean failed, released;
        volatile long retainedBytes;
        View(Key key) { this.key = key; }
    }

    void initialize(Project project) {
        if (disposed || !initialized.add(project)) return;
        enqueue(project, () -> migrate(project));
    }
    boolean isLoaded(Project project, String path) {
        var view = views.get(new Key(project, path)); return view != null && view.model != null && !view.failed;
    }
    String checkoutToken(Project project, String path) {
        var view = views.get(new Key(project, path));
        return view == null || view.model == null || view.failed ? null : view.token;
    }
    void whenLoaded(Project project, String path, String text, Runnable callback) {
        initialize(project);
        var key = new Key(project, path);
        var created = new View(key);
        var previous = views.putIfAbsent(key, created);
        if (previous != null && previous.failed && (callback != null || previous.released)
                && views.replace(key, previous, created)) previous = null;
        var view = previous == null ? created : previous;
        if (text != null) view.requestedText = text;
        if (view.model != null) { view.released = false; if (callback != null) callback.run(); return; }
        if (callback != null) view.callbacks.add(callback);
        if (previous == null) enqueue(project, () -> load(view));
    }
    private View ready(Project project, String path) {
        var key = new Key(project, path);
        var view = views.get(key);
        if (view == null) { whenLoaded(project, path, null, () -> { }); return null; }
        if (view.model == null || view.failed) return null;
        view.released = false;
        return view;
    }
    ReviewHistory history(Project project, String path) {
        var view = ready(project, path);
        return view == null ? ReviewHistory.empty() : view.model.history(project, path);
    }
    ProjectionHints checkpoint(Project project, String path, String text) {
        var view = ready(project, path);
        return view == null ? ProjectionHints.empty() : view.model.checkpoint(project, path, text);
    }
    void checkpoint(Project project, String path, String text, ProjectionHints hints, boolean pending) {
        var view = ready(project, path); if (view == null) return;
        synchronized (view) {
        if (view.model.history(project, path).evidence().isEmpty()) return;
        if (pending) view.model.checkpointPending(project, path, text, hints);
        else view.model.checkpoint(project, path, text, hints);
        view.checkpointTexts.clear(); view.checkpointTexts.put(FileSnapshot.create(text).id(), text);
        persist(view, false, true);
        }
    }
    boolean commitCheckpoint(Project project, String path, ReviewHistoryStore.PreparedCheckpoint value) {
        var view = ready(project, path); if (view == null) return false;
        synchronized (view) {
        if (!view.model.commitCheckpoint(project, path, value)) return false;
        view.checkpointTexts.clear(); view.checkpointTexts.put(FileSnapshot.create(value.text).id(), value.text);
        persist(view, false, true); return true;
        }
    }
    ReviewEvidence mark(Project project, String path, String text, int start, int end) {
        var view = ready(project, path);
        if (view == null) return null;
        synchronized (view) {
        var proof = view.model.mark(project, path, text, start, end);
        if (proof != null) {
            var authors = new HashMap<>(view.authors); authors.put(proof.id(), view.session.author);
            view.authors = Map.copyOf(authors);
            persist(view, true, false);
        }
        return proof;
        }
    }
    void revoke(Project project, String path, List<EvidenceRef> refs) {
        var view = ready(project, path); if (view == null || refs.isEmpty()) return;
        synchronized (view) {
        view.model.revoke(project, path, refs); persist(view, true, false);
        }
    }
    void revokeByEvidence(Project project, List<EvidenceRef> refs) {
        for (var view : List.copyOf(views.values())) {
            if (view.key.project != project || view.model == null) continue;
            var history = view.model.history(project, view.key.path);
            var ids = history.evidence().stream().map(ReviewEvidence::id).collect(java.util.stream.Collectors.toSet());
            var owned = refs.stream().filter(ref -> ids.contains(ref.evidenceId())).toList();
            if (!owned.isEmpty()) revoke(project, view.key.path, owned);
        }
    }
    void clearFile(Project project, String path) {
        var view = ready(project, path); if (view == null) return;
        synchronized (view) {
        view.model.clearFile(project, path); persist(view, true, false);
        }
    }
    List<String> paths(Project project) {
        return views.keySet().stream().filter(key -> key.project == project).map(Key::path).sorted().toList();
    }
    List<String> diagnostics(Project project) { return List.copyOf(warnings.getOrDefault(project, List.of())); }
    String authors(Project project, String path, List<EvidenceRef> refs) {
        var view = views.get(new Key(project, path)); if (view == null) return "";
        return refs.stream().map(ref -> view.authors.get(ref.evidenceId())).filter(java.util.Objects::nonNull)
                .map(SharedHistoryRepository.Author::name).distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    private Session session(Path root, Project project) {
        projectRoots.computeIfAbsent(project, ignored -> new HashSet<>()).add(root);
        return sessions.computeIfAbsent(new SessionKey(project, root), ignored -> new Session(root));
    }
    private void load(View view) {
        var project = view.key.project;
        Path base = Path.of(project.getBasePath());
        Path file = base.resolve(view.key.path);
        Path root = ReviewRepositoryPaths.rootFor(file, base);
        var session = session(root, project);
        session.repository.rescan();
        view.session = session; view.repoPath = ReviewRepositoryPaths.relativePath(root, file);
        view.token = session.repository.checkoutToken();
        if (session.repository.flushRecoverablePending(view.token) > 0) {
            warn(project, "Unpublished review history from an earlier revision was retained locally. Its missing dependencies must be restored before it can be published.");
        }
        var loaded = session.repository.load(view.repoPath);
        view.fileId = session.repository.fileIdFor(view.repoPath);
        var pending = session.repository.pending(view.fileId, view.token);
        var state = pending != null ? pending : loaded == null ? new ReviewHistoryStore.FileState() : loaded.state();
        install(view, state, loaded, -1);
        deliver(project, () -> {
            if (views.get(view.key) != view) return;
            for (var callback : view.callbacks) callback.run();
            view.callbacks.clear(); updated(project, view.key.path, false);
        });
    }
    private boolean install(View view, ReviewHistoryStore.FileState source, SharedHistoryRepository.LoadedFile loaded, long expectedGeneration) {
        // Repository beans are detached; the working model keeps project-relative paths only.
        var state = new ReviewHistoryStore.StoreState(); state.schemaVersion = 2;
        var project = new ReviewHistoryStore.ProjectState(); project.legacyImported = true;
        var file = ReviewHistoryStateCopies.file(source); file.path = view.key.path;
        project.files.put(view.fileId, file); project.aliases.put(view.key.path, view.fileId);
        state.projects.put(LegacyReviewHistoryModel.projectKey(view.key.project), project);
        var model = new LegacyReviewHistoryModel(ReviewHistoryStore.backupDirectory());
        model.loadDetachedState(state);
        model.history(view.key.project, view.key.path);
        model.checkpoint(view.key.project, view.key.path, view.requestedText);
        model.checkpoint(view.key.project, view.key.path, "\u0000doreview-prime-" + java.util.UUID.randomUUID());
        synchronized (view) {
            if (expectedGeneration >= 0 && view.generation != expectedGeneration) return false;
            view.persisted = loaded;
            if (loaded != null) { view.authors = loaded.evidenceAuthors(); view.observedFrontier = loaded.frontier(); }
            view.model = model;
            view.retainedBytes = source.snapshots.values().stream().mapToLong(text -> 2L * text.length()).sum();
        }
        return true;
    }
    private void persist(View view, boolean publish, boolean coalesce) {
        synchronized (view) {
        var captured = view.model.captureFile(view.key.project, view.key.path);
        if (captured == null || captured.history().evidence().isEmpty()) return;
        long generation = ++view.generation;
        long pending = ++view.pendingGeneration;
        var targetTexts = Map.copyOf(view.checkpointTexts);
        var observed = view.observedFrontier;
        String token = view.token;
        enqueue(view.key.project, () -> {
            if (coalesce && pending != view.pendingGeneration) return;
            var file = captured.encode(); file.path = view.repoPath; file.snapshots.putAll(targetTexts);
            var repository = view.session.repository;
            // A previous local command in this ordered queue is observed by the next one.
            var parents = new HashSet<>(observed);
            parents.addAll(view.localPublications);
            repository.savePending(view.fileId, view.repoPath, file, view.session.author, token, parents);
            if (!publish) return;
            var before = view.persisted == null ? null : view.persisted.state();
            var loaded = repository.publish(view.fileId, view.repoPath, before, file, view.session.author, parents, token);
            String published = repository.lastPublishedEventId();
            if (published != null) { view.localPublications.clear(); view.localPublications.add(published); }
            repository.acknowledgePending(view.fileId, token);
            if (loaded != null) view.persisted = loaded;
            if (loaded != null && generation == view.generation) {
                if (install(view, loaded.state(), loaded, generation))
                    deliver(view.key.project, () -> updated(view.key.project, view.key.path, false));
            }
        });
        }
    }
    void flush(Project project) {
        for (var view : List.copyOf(views.values())) if (view.key.project == project && view.model != null) persist(view, true, false);
    }
    void flush(Project project, String path) {
        var view = views.get(new Key(project, path));
        if (view != null && view.model != null) persist(view, true, false);
    }
    void reload(Project project) {
        var active = views.values().stream().filter(view -> view.key.project == project).toList();
        for (var view : active) {
            if (!views.remove(view.key, view)) continue;
            var waiting = List.copyOf(view.callbacks); view.callbacks.clear();
            whenLoaded(project, view.key.path, view.requestedText, null);
            var replacement = views.get(view.key);
            if (replacement != null) replacement.callbacks.addAll(waiting);
        }
        updated(project, null, true);
    }
    void release(Project project, String path) {
        var view = views.get(new Key(project, path)); if (view == null) return;
        view.released = true;
        if (view.model != null) persist(view, true, false);
        var idle = views.values().stream().filter(candidate -> candidate.released).toList();
        long retained = idle.stream().mapToLong(candidate -> candidate.retainedBytes).sum();
        for (int i = 0; i < idle.size(); i++) {
            if (idle.size() - i <= MAX_IDLE_FILES && retained <= 64L * 1024 * 1024) break;
            var old = idle.get(i); views.remove(old.key, old); retained -= old.retainedBytes;
        }
    }
    boolean movePath(Project project, String oldPath, String newPath) {
        movePathAsync(project, oldPath, newPath, ignored -> { }); return true;
    }
    void movePathAsync(Project project, String oldPath, String newPath, Consumer<Boolean> callback) {
        // Fence the old path before another save can queue an operation against it.
        var affected = views.values().stream().filter(view -> view.key.project == project
                && (view.key.path.equals(oldPath) || view.key.path.startsWith(oldPath + "/"))).toList();
        for (var view : affected) {
            if (view.model != null) persist(view, true, false);
            views.remove(view.key, view);
        }
        enqueue(project, () -> {
            Path base = Path.of(project.getBasePath());
            Path root = ReviewRepositoryPaths.rootFor(base.resolve(newPath), base);
            if (!root.equals(ReviewRepositoryPaths.rootFor(base.resolve(oldPath), base))) { deliver(project, () -> callback.accept(false)); return; }
            var repository = session(root, project).repository;
            String oldRepo = ReviewRepositoryPaths.relativePath(root, base.resolve(oldPath));
            String newRepo = ReviewRepositoryPaths.relativePath(root, base.resolve(newPath));
            var all = repository.listFiles(); boolean moved = false;
            for (var entry : all.entrySet()) {
                if (!entry.getKey().equals(oldRepo) && !entry.getKey().startsWith(oldRepo + "/")) continue;
                String destination = newRepo + entry.getKey().substring(oldRepo.length());
                if (all.containsKey(destination) && !all.get(destination).equals(entry.getValue())) continue;
                var before = repository.load(entry.getKey()); if (before == null) continue;
                var after = ReviewHistoryStateCopies.file(before.state()); after.path = destination;
                repository.publish(before.fileId(), destination, before.state(), after, session(root, project).author, before.frontier()); moved = true;
            }
            boolean result = moved;
            deliver(project, () -> { reload(project); callback.accept(result); });
        });
    }
    void queryVersions(Project project, String path, Consumer<List<SharedHistoryRepository.Version>> callback) {
        whenLoaded(project, path, null, () -> {
            var view = ready(project, path); if (view == null) return;
            enqueue(project, () -> {
                var versions = view.session.repository.versions(view.fileId);
                deliver(project, () -> callback.accept(versions));
            });
        });
    }
    void readSnapshot(Project project, String path, String hash, Consumer<String> callback) {
        var view = ready(project, path); if (view == null) return;
        enqueue(project, () -> { String text = view.session.repository.snapshot(hash); deliver(project, () -> callback.accept(text)); });
    }
    void queryPaths(Project project, Consumer<List<String>> callback) {
        initialize(project);
        enqueue(project, () -> {
            var result = new java.util.TreeSet<String>();
            Path base = ReviewRepositoryPaths.canonicalRoot(Path.of(project.getBasePath()));
            var roots = new HashSet<Path>(); roots.add(ReviewRepositoryPaths.rootFor(base, base));
            for (var view : views.values()) if (view.key.project == project && view.session != null) roots.add(view.session.repository.worktreeRoot());
            for (var root : roots) for (String path : session(root, project).repository.listFiles().keySet()) {
                Path file = root.resolve(path);
                if (file.startsWith(base)) result.add(base.relativize(file).toString().replace('\\', '/'));
            }
            deliver(project, () -> callback.accept(List.copyOf(result)));
        });
    }
    void rebuild(Project project, String path, Consumer<Path> callback) {
        flush(project);
        enqueue(project, () -> {
            Path base = Path.of(project.getBasePath());
            Path root = ReviewRepositoryPaths.rootFor(base.resolve(path), base);
            Path backup = session(root, project).repository.rebuildIndex();
            deliver(project, () -> { reload(project); callback.accept(backup); });
        });
    }
    ReviewHistoryStore.MigrationResult importLegacy(Project project, Map<String, ReviewHistoryStore.LegacyFile> files) {
        enqueue(project, () -> {
            var model = new LegacyReviewHistoryModel(ReviewHistoryStore.backupDirectory());
            model.importLegacy(project, files); importProjectState(project, model.getState(), "explicit");
            deliver(project, () -> reload(project));
        });
        return ReviewHistoryStore.MigrationResult.empty();
    }
    private void migrate(Project project) throws Exception {
        Path xml = Path.of(System.getProperty("doreview.legacy.xml.path", PathManager.getConfigDir().resolve("options/review-marks-history.xml").toString()));
        String sourcePath = ReviewRepositoryPaths.canonicalRoot(xml).toString();
        Path base = Path.of(project.getBasePath());
        Path root = ReviewRepositoryPaths.rootFor(base, base);
        Path complete = ReviewRepositoryPaths.databasePath(root).getParent().resolve("migration-"
                + ReviewRepositoryPaths.sha256(sourcePath + "\n" + LegacyReviewHistoryModel.projectKey(project)) + ".complete");
        // The legacy XML may contain every project ever opened. Once this project's
        // import succeeds, later starts must not deserialize that archive again.
        if (Files.isRegularFile(complete)) return;
        var model = new LegacyReviewHistoryModel(ReviewHistoryStore.backupDirectory());
        String sourceId = "legacy-project";
        if (Files.isRegularFile(xml)) {
            byte[] raw = Files.readAllBytes(xml);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            sourceId = sourcePath;
            Path backup = ReviewHistoryStore.backupDirectory().resolve("history-" + digest + ".xml");
            Files.createDirectories(backup.getParent());
            if (!Files.exists(backup)) Files.write(backup, raw, java.nio.file.StandardOpenOption.CREATE_NEW);
            var xmlRoot = JDOMUtil.load(new ByteArrayInputStream(raw));
            var component = xmlRoot.getName().equals("component") ? xmlRoot : xmlRoot.getChildren("component").stream()
                    .filter(child -> "ReviewMarksHistory".equals(child.getAttributeValue("name"))).findFirst().orElse(null);
            if (component != null) {
                var state = XmlSerializer.deserialize(component, ReviewHistoryStore.StoreState.class);
                model.loadDetachedState(state);
                if (state.schemaVersion > 2) throw new IllegalStateException("Unsupported legacy history schema; XML preserved");
            }
        }
        var legacyResult = model.importLegacyFromProject(project);
        for (var message : legacyResult.warnings()) warn(project, message);
        var importedState = model.getState();
        var importedProject = importedState.projects.get(LegacyReviewHistoryModel.projectKey(project));
        if (importedProject != null && !importedProject.legacyImported) {
            throw new IllegalStateException("Legacy project history import is incomplete; its source and backup were retained for retry");
        }
        importProjectState(project, importedState, sourceId);
        Files.createDirectories(complete.getParent());
        Path temporary = Files.createTempFile(complete.getParent(), "migration-", ".pending");
        try {
            Files.writeString(temporary, "Imported history\n", StandardCharsets.UTF_8);
            Files.move(temporary, complete, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void importProjectState(Project project, ReviewHistoryStore.StoreState state, String sourceId) throws Exception {
        var entry = state.projects.get(LegacyReviewHistoryModel.projectKey(project));
        if (entry == null || entry.files.isEmpty()) return;
        Path base = Path.of(project.getBasePath());
        var completed = new HashSet<Path>();
        for (var record : entry.files.entrySet()) {
            var original = record.getValue();
            Path file = base.resolve(original.path);
            Path root = ReviewRepositoryPaths.rootFor(file, base);
            var repository = session(root, project).repository;
            Path marker = repository.databasePath().getParent().resolve("legacy-" + ReviewRepositoryPaths.sha256(sourceId + LegacyReviewHistoryModel.projectKey(project)) + ".complete");
            if (Files.exists(marker)) continue;
            var converted = ReviewHistoryStateCopies.file(original);
            converted.path = ReviewRepositoryPaths.relativePath(root, file);
            ReviewJournalCodec.read(converted); // validate before publication
            var existing = repository.load(converted.path);
            String id = existing == null ? ReviewRepositoryPaths.sha256("file\n" + converted.path) : existing.fileId();
            // Legacy data never observed modern shared checkpoints. Import as independent
            // provenance, so old locators cannot causally supersede a newer identity denial.
            var loaded = repository.publish(id, converted.path, null, converted,
                    SharedHistoryRepository.Author.imported(), Set.of());
            // Keep previous aliases: a historical rename remains usable after an older checkout.
            for (var alias : entry.aliases.entrySet()) if (alias.getValue().equals(record.getKey()) && !alias.getKey().equals(original.path)) {
                Path old = base.resolve(alias.getKey());
                if (!ReviewRepositoryPaths.rootFor(old, base).equals(root)) continue;
                String relative = ReviewRepositoryPaths.relativePath(root, old);
                var before = ReviewHistoryStateCopies.file(loaded.state()); before.path = relative;
                var aliased = ReviewHistoryStateCopies.file(loaded.state()); aliased.path = converted.path;
                loaded = repository.publish(id, converted.path, before, aliased, SharedHistoryRepository.Author.imported(), loaded.frontier());
            }
            completed.add(marker);
        }
        for (var marker : completed) Files.writeString(marker, "Imported history\n", StandardCharsets.UTF_8);
    }
    private interface Work { void run() throws Exception; }
    private void poll() {
        if (disposed) return;
        try { worker.execute(() -> {
            var changedProjects = new HashSet<Project>();
            for (var entry : sessions.entrySet()) {
                try {
                    if (entry.getValue().repository.rescan()) changedProjects.add(entry.getKey().project);
                } catch (RuntimeException failure) {
                    var project = entry.getKey().project;
                    warn(project, "Shared review history is incomplete or unreadable; confirmed marks are hidden."); changedProjects.add(project);
                }
            }
            for (var project : changedProjects) deliver(project, () -> reload(project));
        }); } catch (java.util.concurrent.RejectedExecutionException closed) { }
    }
    void projectClosed(Project project) {
        flush(project);
        views.keySet().removeIf(key -> key.project == project);
        enqueue(project, () -> {
            projectRoots.remove(project);
            var iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().project == project) { entry.getValue().repository.close(); iterator.remove(); }
            }
            initialized.remove(project); warnings.remove(project);
        });
    }
    private void enqueue(Project project, Work task) {
        if (disposed) return;
        worker.execute(() -> {
            try { task.run(); }
            catch (Throwable failure) {
                for (var view : views.values()) if (view.key.project == project && view.model == null) view.failed = true;
                warn(project, "Review history: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
                LOG.warn("DoReview storage operation failed; preserved history", failure);
                deliver(project, () -> updated(project, null, false));
            }
        });
    }
    private void warn(Project project, String message) {
        warnings.computeIfAbsent(project, ignored -> new CopyOnWriteArrayList<>());
        if (!warnings.get(project).contains(message)) {
            warnings.get(project).add(message); deliver(project, () -> updated(project, null, false));
        }
    }
    private static void deliver(Project project, Runnable callback) {
        var application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed() || project.isDisposed()) return;
        application.invokeLater(() -> { if (!application.isDisposed() && !project.isDisposed()) callback.run(); }, ModalityState.any());
    }
    private static void updated(Project project, String path, boolean replacement) {
        var app = ApplicationManager.getApplication();
        if (app != null && !app.isDisposed())
            app.getMessageBus().syncPublisher(ReviewHistoryStore.SHARED_UPDATES).historyUpdated(project, path, replacement);
    }
    private static String gitConfig(Path root, String key, String fallback) {
        try {
            var process = new ProcessBuilder("git", "-C", root.toString(), "config", "--get", key).redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return fallback; }
            if (process.exitValue() != 0) return fallback;
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return value.isBlank() ? fallback : value;
        } catch (Exception unavailable) { return fallback; }
    }
    @Override public void dispose() {
        if (disposed) return;
        // Already queued pending writes finish before connection closure; no JDBC work on EDT.
        worker.execute(() -> {
            try {
            for (var session : sessions.values()) {
                try { session.repository.flushAllPending(session.repository.checkoutToken()); }
                catch (RuntimeException failure) { LOG.warn("Retained unflushed DoReview pending journal", failure); }
                session.repository.close();
            }
            sessions.clear();
            } finally { closed.complete(null); }
        });
        disposed = true; watcher.shutdownNow(); worker.shutdown(); views.clear();
    }
}
