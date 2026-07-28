/*
 * Copyright (c) 2026 Ares16x16.
 * SPDX-License-Identifier: EPL-2.0
 */

package com.example.rtc.exporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.ibm.team.filesystem.client.internal.namespace.IItemContext;
import com.ibm.team.filesystem.common.changemodel.VersionablePath;
import com.ibm.team.filesystem.ui.configuration.IHistoryEntry;
import com.ibm.team.filesystem.ui.wrapper.ChangeSetWrapper;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.scm.common.IChange;
import com.ibm.team.scm.common.IChangeSet;
import com.ibm.team.scm.common.IVersionable;
import com.ibm.team.scm.common.internal.util.ItemId;

final class HistoryExporter {
    private HistoryExporter() {
    }

    static String entryLabel(IHistoryEntry entry) {
        IChangeSet changeSet = changeSet(entry);
        if (changeSet == null) {
            return String.valueOf(entry);
        }
        String comment = safe(changeSet.getComment()).strip();
        if (comment.isEmpty()) {
            comment = "Change set " + changeSet.getItemId();
        }
        Date date = entry.getDeliveryTime() != null ? entry.getDeliveryTime() : entry.getCreationTime();
        return date == null ? comment : comment + " — " + date;
    }

    static HistoryResult prepare(List<IHistoryEntry> entries, IProgressMonitor monitor) {
        HistoryResult result = new HistoryResult(Instant.now().toString());
        for (IHistoryEntry entry : entries) {
            checkCanceled(monitor);
            IChangeSet changeSet = changeSet(entry);
            if (changeSet == null) {
                continue;
            }
            HistoryChangeSet exported = new HistoryChangeSet(
                    entryLabel(entry), String.valueOf(changeSet.getItemId()),
                    safe(changeSet.getComment()), text(changeSet.getAuthor()),
                    date(entry.getCreationTime()), date(entry.getDeliveryTime()),
                    changeSet.isActive());

            List<IChange> changes = changes(changeSet);
            List<ItemId<IVersionable>> itemIds = new ArrayList<>();
            for (IChange change : changes) {
                itemIds.add(ItemId.create(change.item()));
            }
            IItemContext afterContext = entry.getDynamicContext();
            IItemContext beforeContext = previousContext(entry, monitor);
            Map<ItemId<IVersionable>, VersionablePath> beforePaths = resolve(beforeContext, itemIds, monitor);
            Map<ItemId<IVersionable>, VersionablePath> afterPaths = resolve(afterContext, itemIds, monitor);
            ITeamRepository repository = entry.getRepository();

            for (int index = 0; index < changes.size(); index++) {
                checkCanceled(monitor);
                IChange change = changes.get(index);
                ItemId<IVersionable> itemId = itemIds.get(index);
                String fallback = String.valueOf(itemId);
                String beforePath = path(beforePaths.get(itemId), fallback);
                String afterPath = path(afterPaths.get(itemId), fallback);
                if (change.kind() == IChange.ADD) {
                    beforePath = afterPath;
                } else if (change.kind() == IChange.DELETE) {
                    afterPath = beforePath;
                }
                exported.files.add(new HistoryFile(
                        operation(change.kind()), beforePath, afterPath,
                        GitPatchExporter.repositoryChange(repository, change, beforePath, afterPath)));
            }
            result.changeSets.add(exported);
        }
        return result;
    }

    static HistoryResult filter(HistoryResult source, Set<Object> selected) {
        HistoryResult filtered = new HistoryResult(source.generatedAt);
        for (HistoryChangeSet changeSet : source.changeSets) {
            boolean allFiles = selected.contains(changeSet);
            HistoryChangeSet selectedChangeSet = changeSet.copyWithoutFiles();
            for (HistoryFile file : changeSet.files) {
                if (allFiles || selected.contains(file)) {
                    selectedChangeSet.files.add(file);
                }
            }
            if (allFiles || !selectedChangeSet.files.isEmpty()) {
                filtered.changeSets.add(selectedChangeSet);
            }
        }
        return filtered;
    }

    static List<Object> allNodes(HistoryResult result) {
        List<Object> nodes = new ArrayList<>();
        for (HistoryChangeSet changeSet : result.changeSets) {
            nodes.add(changeSet);
            nodes.addAll(changeSet.files);
        }
        return nodes;
    }

    static void write(HistoryResult result, Path output, IProgressMonitor monitor) throws IOException {
        Files.createDirectories(output);
        List<Object> patchElements = new ArrayList<>();
        for (HistoryChangeSet changeSet : result.changeSets) {
            for (HistoryFile file : changeSet.files) {
                patchElements.add(file.patchElement);
            }
        }
        result.patchSummary = GitPatchExporter.write(
                patchElements, output.resolve("rtc-history.patch"), monitor);
        Files.writeString(output.resolve("rtc-history.json"), toJson(result), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("rtc-history.md"), toMarkdown(result), StandardCharsets.UTF_8);
    }

    private static IChangeSet changeSet(IHistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        ChangeSetWrapper wrapper = entry.getChangeSet();
        return wrapper == null ? null : wrapper.getChangeSet();
    }

    private static List<IChange> changes(IChangeSet changeSet) {
        List<IChange> result = new ArrayList<>();
        for (Object value : changeSet.changes()) {
            if (value instanceof IChange) {
                result.add((IChange) value);
            }
        }
        return result;
    }

    private static IItemContext previousContext(IHistoryEntry entry, IProgressMonitor monitor) {
        try {
            IHistoryEntry previous = entry.getPrevious(monitor);
            return previous == null ? null : previous.getDynamicContext();
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<ItemId<IVersionable>, VersionablePath> resolve(
            IItemContext context,
            List<ItemId<IVersionable>> itemIds,
            IProgressMonitor monitor) {
        if (context == null || itemIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return context.resolve(itemIds, monitor);
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private static String path(VersionablePath path, String fallback) {
        if (path == null || !path.isResolved()) {
            return fallback;
        }
        String value = path.toPath().toPortableString();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.isBlank() ? fallback : value;
    }

    private static String operation(int kind) {
        switch (kind) {
            case IChange.ADD: return "add";
            case IChange.MODIFY: return "modify";
            case IChange.RENAME: return "rename";
            case IChange.REPARENT: return "move";
            case IChange.DELETE: return "delete";
            default: return "change";
        }
    }

    private static String toJson(HistoryResult result) {
        StringBuilder json = new StringBuilder(16384);
        json.append("{\n");
        json.append("  \"format\": \"rtc-eclipse-history\",\n");
        json.append("  \"formatVersion\": 1,\n");
        json.append("  \"generatedAt\": ").append(quote(result.generatedAt)).append(",\n");
        json.append("  \"source\": \"Eclipse EWM History view\",\n");
        json.append("  \"summary\": {\n");
        json.append("    \"changeSets\": ").append(result.changeSets.size()).append(",\n");
        json.append("    \"files\": ").append(result.getFileCount()).append(",\n");
        json.append("    \"exportedDiffs\": ").append(result.patchSummary.getExportedChanges()).append(",\n");
        json.append("    \"skippedDiffs\": ").append(result.patchSummary.getSkippedChanges()).append("\n");
        json.append("  },\n");
        json.append("  \"patch\": {\n");
        json.append("    \"file\": \"rtc-history.patch\",\n");
        json.append("    \"exportedChanges\": ").append(result.patchSummary.getExportedChanges()).append(",\n");
        json.append("    \"binaryChanges\": ").append(result.patchSummary.getBinaryChanges()).append(",\n");
        json.append("    \"skippedChanges\": ").append(result.patchSummary.getSkippedChanges()).append(",\n");
        json.append("    \"bytes\": ").append(result.patchSummary.getPatchBytes()).append(",\n");
        json.append("    \"skippedReasons\": [");
        List<String> skippedReasons = result.patchSummary.getSkippedReasons();
        for (int index = 0; index < skippedReasons.size(); index++) {
            json.append(index == 0 ? "\n" : ",\n");
            json.append("      ").append(quote(skippedReasons.get(index)));
        }
        if (!skippedReasons.isEmpty()) {
            json.append('\n');
        }
        json.append("    ]\n  },\n");
        json.append("  \"changeSets\": [");
        for (int index = 0; index < result.changeSets.size(); index++) {
            json.append(index == 0 ? "\n" : ",\n");
            appendChangeSet(json, result.changeSets.get(index));
        }
        if (!result.changeSets.isEmpty()) {
            json.append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static void appendChangeSet(StringBuilder json, HistoryChangeSet changeSet) {
        json.append("    {\n");
        json.append("      \"id\": ").append(quote(changeSet.id)).append(",\n");
        json.append("      \"comment\": ").append(quote(changeSet.comment)).append(",\n");
        json.append("      \"author\": ").append(quote(changeSet.author)).append(",\n");
        json.append("      \"createdAt\": ").append(quote(changeSet.createdAt)).append(",\n");
        json.append("      \"deliveredAt\": ").append(quote(changeSet.deliveredAt)).append(",\n");
        json.append("      \"active\": ").append(changeSet.active).append(",\n");
        json.append("      \"files\": [");
        for (int index = 0; index < changeSet.files.size(); index++) {
            HistoryFile file = changeSet.files.get(index);
            json.append(index == 0 ? "\n" : ",\n");
            json.append("        {\n");
            json.append("          \"operation\": ").append(quote(file.operation)).append(",\n");
            json.append("          \"beforePath\": ").append(quote(file.beforePath)).append(",\n");
            json.append("          \"afterPath\": ").append(quote(file.afterPath)).append("\n");
            json.append("        }");
        }
        if (!changeSet.files.isEmpty()) {
            json.append('\n');
        }
        json.append("      ]\n    }");
    }

    private static String toMarkdown(HistoryResult result) {
        StringBuilder markdown = new StringBuilder(8192);
        markdown.append("# RTC History Export\n\n");
        markdown.append("Generated: ").append(result.generatedAt).append("\n\n");
        markdown.append("## Summary\n\n");
        markdown.append("- Selected change sets: ").append(result.changeSets.size()).append('\n');
        markdown.append("- Selected files: ").append(result.getFileCount()).append('\n');
        markdown.append("- Exported diffs: ").append(result.patchSummary.getExportedChanges()).append('\n');
        markdown.append("- Binary diffs: ").append(result.patchSummary.getBinaryChanges()).append('\n');
        markdown.append("- Skipped diffs: ").append(result.patchSummary.getSkippedChanges()).append("\n\n");
        markdown.append("Patch: [rtc-history.patch](rtc-history.patch)\n\n");
        if (!result.patchSummary.getSkippedReasons().isEmpty()) {
            markdown.append("### Skipped Diff Details\n\n");
            for (String reason : result.patchSummary.getSkippedReasons()) {
                markdown.append("- ").append(reason.replace("\n", " ")).append('\n');
            }
            markdown.append('\n');
        }
        markdown.append("## Change Sets\n\n");
        for (HistoryChangeSet changeSet : result.changeSets) {
            markdown.append("- ").append(oneLine(changeSet.label)).append('\n');
            markdown.append("  - ID: ").append(changeSet.id).append("\n");
            markdown.append("  - Author: ").append(oneLine(changeSet.author)).append('\n');
            markdown.append("  - Created: ").append(changeSet.createdAt).append('\n');
            markdown.append("  - Delivered: ").append(changeSet.deliveredAt).append('\n');
            for (HistoryFile file : changeSet.files) {
                markdown.append("  - [").append(file.operation).append("] ")
                        .append(oneLine(file.getLabel())).append('\n');
            }
        }
        return markdown.toString();
    }

    private static String quote(String value) {
        return PendingChangesExporter.quote(safe(value));
    }

    private static String oneLine(String value) {
        return safe(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static String date(Date value) {
        return value == null ? "" : value.toInstant().toString();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void checkCanceled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }

    static final class HistoryResult {
        private final String generatedAt;
        private final List<HistoryChangeSet> changeSets = new ArrayList<>();
        private GitPatchExporter.PatchSummary patchSummary = new GitPatchExporter.PatchSummary();

        private HistoryResult(String generatedAt) {
            this.generatedAt = generatedAt;
        }

        List<HistoryChangeSet> getChangeSets() {
            return Collections.unmodifiableList(changeSets);
        }

        int getFileCount() {
            int count = 0;
            for (HistoryChangeSet changeSet : changeSets) {
                count += changeSet.files.size();
            }
            return count;
        }

        int getExportedPatchCount() {
            return patchSummary.getExportedChanges();
        }
    }

    static final class HistoryChangeSet {
        private final String label;
        private final String id;
        private final String comment;
        private final String author;
        private final String createdAt;
        private final String deliveredAt;
        private final boolean active;
        private final List<HistoryFile> files = new ArrayList<>();

        private HistoryChangeSet(
                String label,
                String id,
                String comment,
                String author,
                String createdAt,
                String deliveredAt,
                boolean active) {
            this.label = label;
            this.id = id;
            this.comment = comment;
            this.author = author;
            this.createdAt = createdAt;
            this.deliveredAt = deliveredAt;
            this.active = active;
        }

        private HistoryChangeSet copyWithoutFiles() {
            return new HistoryChangeSet(label, id, comment, author, createdAt, deliveredAt, active);
        }

        String getLabel() {
            return label;
        }

        List<HistoryFile> getFiles() {
            return Collections.unmodifiableList(files);
        }
    }

    static final class HistoryFile {
        private final String operation;
        private final String beforePath;
        private final String afterPath;
        private final GitPatchExporter.RepositoryChange patchElement;

        private HistoryFile(
                String operation,
                String beforePath,
                String afterPath,
                GitPatchExporter.RepositoryChange patchElement) {
            this.operation = operation;
            this.beforePath = beforePath;
            this.afterPath = afterPath;
            this.patchElement = patchElement;
        }

        String getLabel() {
            if (beforePath.equals(afterPath)) {
                return afterPath;
            }
            return beforePath + " → " + afterPath;
        }
    }
}
