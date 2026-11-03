package dev.doreview.ide;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Legacy XML import and completion markers, called only by the storage worker. */
final class SharedHistoryLegacyImporter {
    private final BiFunction<Project, Path, SharedHistoryRepository> repositories;
    private final BiConsumer<Project, String> warnings;

    SharedHistoryLegacyImporter(BiFunction<Project, Path, SharedHistoryRepository> repositories,
                                BiConsumer<Project, String> warnings) {
        this.repositories = repositories;
        this.warnings = warnings;
    }

    void migrate(Project project) throws Exception {
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
        for (var message : legacyResult.warnings()) warnings.accept(project, message);
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
    void importProjectState(Project project, ReviewHistoryStore.StoreState state, String sourceId) throws Exception {
        var entry = state.projects.get(LegacyReviewHistoryModel.projectKey(project));
        if (entry == null || entry.files.isEmpty()) return;
        Path base = Path.of(project.getBasePath());
        var completed = new HashSet<Path>();
        for (var record : entry.files.entrySet()) {
            var original = record.getValue();
            Path file = base.resolve(original.path);
            Path root = ReviewRepositoryPaths.rootFor(file, base);
            var repository = repositories.apply(project, root);
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
}
