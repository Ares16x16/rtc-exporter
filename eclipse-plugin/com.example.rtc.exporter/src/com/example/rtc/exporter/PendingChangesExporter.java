/*
 * Copyright (c) 2026 Ares16x16.
 * SPDX-License-Identifier: EPL-2.0
 */

package com.example.rtc.exporter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;

public final class PendingChangesExporter {
    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 50000;
    private static final String[] PROPERTY_METHODS = {
            "getName", "getComment", "getSummary", "getDescription", "getPath",
            "getResultingPath", "getOriginalPath", "getRepositoryPath", "getType",
            "getState", "getId", "getItemId", "getUUID", "getUserId", "isComplete",
            "isCurrent", "isConflicting", "isIncoming", "isOutgoing", "isCanceled"
    };

    private PendingChangesExporter() {
    }

    public static ExportResult capture(TreeViewer viewer) {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider)) {
            throw new IllegalStateException("RTC Pending Changes does not expose a tree content provider");
        }
        ITreeContentProvider contentProvider = (ITreeContentProvider) viewer.getContentProvider();
        IBaseLabelProvider baseLabelProvider = viewer.getLabelProvider();
        ILabelProvider labelProvider = baseLabelProvider instanceof ILabelProvider
                ? (ILabelProvider) baseLabelProvider
                : null;

        ExportResult result = new ExportResult(Instant.now().toString());
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Object[] roots = contentProvider.getElements(viewer.getInput());
        if (roots != null) {
            for (Object root : roots) {
                result.roots.add(captureNode(root, "other", 0, contentProvider, labelProvider, visited, result));
                if (result.nodeCount >= MAX_NODES) {
                    result.truncated = true;
                    break;
                }
            }
        }
        return result;
    }

    private static ExportNode captureNode(
            Object element,
            String inheritedSection,
            int depth,
            ITreeContentProvider contentProvider,
            ILabelProvider labelProvider,
            Set<Object> visited,
            ExportResult result) {
        String label = labelFor(element, labelProvider);
        String type = element == null ? "null" : element.getClass().getName();
        String section = classifySection(type, label, inheritedSection);
        String kind = classifyKind(type, label);
        ExportNode node = new ExportNode(label, type, section, kind, extractProperties(element));
        result.nodeCount++;
        result.nodesBySection.put(section, result.nodesBySection.getOrDefault(section, 0) + 1);
        if (GitPatchExporter.supports(element)) {
            result.patchElements.add(element);
        }

        if (element == null || depth >= MAX_DEPTH || result.nodeCount >= MAX_NODES) {
            if (depth >= MAX_DEPTH || result.nodeCount >= MAX_NODES) {
                node.properties.put("truncated", "true");
                result.truncated = true;
            }
            return node;
        }
        if (!visited.add(element)) {
            node.properties.put("repeatedReference", "true");
            return node;
        }

        Object[] children;
        try {
            children = contentProvider.getChildren(element);
        } catch (RuntimeException error) {
            node.properties.put("childrenError", error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
            return node;
        }
        if (children != null) {
            for (Object child : children) {
                if (isLoadingPlaceholder(child, labelFor(child, labelProvider))) {
                    result.loadingParents.add(element);
                }
                if (result.nodeCount >= MAX_NODES) {
                    result.truncated = true;
                    break;
                }
                node.children.add(captureNode(
                        child, section, depth + 1, contentProvider, labelProvider, visited, result));
            }
        }
        return node;
    }

    private static boolean isLoadingPlaceholder(Object element, String label) {
        if (!(element instanceof String)) {
            return false;
        }
        String normalized = safe(label).strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("pending") || normalized.startsWith("loading");
    }

    private static String labelFor(Object element, ILabelProvider labelProvider) {
        if (element == null) {
            return "<null>";
        }
        if (labelProvider != null) {
            try {
                String label = labelProvider.getText(element);
                if (label != null && !label.isBlank()) {
                    return label;
                }
            } catch (RuntimeException ignored) {
                // Fall back to the model object's text.
            }
        }
        return safe(String.valueOf(element));
    }

    private static Map<String, String> extractProperties(Object element) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (element == null) {
            return properties;
        }
        for (String methodName : PROPERTY_METHODS) {
            try {
                Method method = element.getClass().getMethod(methodName);
                if (method.getParameterCount() != 0 || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                Object value = method.invoke(element);
                if (value != null) {
                    String text = safe(String.valueOf(value));
                    if (!text.isBlank() && text.length() <= 2000) {
                        properties.put(propertyName(methodName), text);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // RTC model implementations vary by release. Missing properties are expected.
            }
        }
        return properties;
    }

    private static String propertyName(String methodName) {
        String name = methodName.startsWith("is") ? methodName.substring(2) : methodName.substring(3);
        if (name.isEmpty()) {
            return methodName;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String classifySection(String type, String label, String inherited) {
        String value = (type + " " + label).toLowerCase(Locale.ROOT);
        if (value.contains("conflict")) {
            return "conflicts";
        }
        if (value.contains("unresolved") || value.contains("localchange")) {
            return "local-unresolved";
        }
        if (value.contains("incoming")) {
            return "incoming";
        }
        if (value.contains("outgoing")) {
            return "outgoing";
        }
        if (value.contains("suspended")) {
            return "suspended";
        }
        return inherited;
    }

    private static String classifyKind(String type, String label) {
        String value = (type + " " + label).toLowerCase(Locale.ROOT);
        if (value.contains("baseline") || value.contains("snapshot")) {
            return "baseline";
        }
        if (value.contains("workspace")) {
            return "workspace";
        }
        if (value.contains("component")) {
            return "component";
        }
        if (value.contains("changeset") || value.contains("remoteactivity")) {
            return "change-set";
        }
        if (value.contains("workitem")) {
            return "work-item";
        }
        if (value.contains("folder") || value.contains("source")) {
            return "folder";
        }
        if (value.contains("change") || value.contains("item")) {
            return "changed-item";
        }
        return "node";
    }

    public static void write(ExportResult result, Path output, IProgressMonitor monitor) throws IOException {
        Files.createDirectories(output);
        result.patchSummary = GitPatchExporter.write(
                result.patchElements, output.resolve("rtc-pending-changes.patch"), monitor);
        Files.writeString(output.resolve("rtc-pending-changes.json"), toJson(result), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("rtc-pending-changes.md"), toMarkdown(result), StandardCharsets.UTF_8);
    }

    private static String toJson(ExportResult result) {
        StringBuilder json = new StringBuilder(16384);
        json.append("{\n");
        json.append("  \"format\": \"rtc-eclipse-pending-changes\",\n");
        json.append("  \"formatVersion\": 2,\n");
        json.append("  \"generatedAt\": ").append(quote(result.generatedAt)).append(",\n");
        json.append("  \"source\": \"Eclipse RTC Pending Changes view\",\n");
        json.append("  \"summary\": {\n");
        json.append("    \"nodes\": ").append(result.nodeCount).append(",\n");
        json.append("    \"truncated\": ").append(result.truncated).append(",\n");
        json.append("    \"nodesBySection\": ");
        appendStringIntegerMap(json, result.nodesBySection, 4);
        json.append("\n  },\n");
        json.append("  \"patch\": {\n");
        json.append("    \"file\": \"rtc-pending-changes.patch\",\n");
        json.append("    \"exportedChanges\": ").append(result.patchSummary.getExportedChanges()).append(",\n");
        json.append("    \"binaryChanges\": ").append(result.patchSummary.getBinaryChanges()).append(",\n");
        json.append("    \"skippedChanges\": ").append(result.patchSummary.getSkippedChanges()).append(",\n");
        json.append("    \"bytes\": ").append(result.patchSummary.getPatchBytes()).append(",\n");
        json.append("    \"skippedReasons\": [");
        List<String> skippedReasons = result.patchSummary.getSkippedReasons();
        for (int index = 0; index < skippedReasons.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("\n      ").append(quote(skippedReasons.get(index)));
        }
        if (!skippedReasons.isEmpty()) {
            json.append('\n').append("    ");
        }
        json.append("]\n  },\n");
        json.append("  \"pendingChanges\": [\n");
        for (int index = 0; index < result.roots.size(); index++) {
            appendNode(json, result.roots.get(index), 4);
            if (index + 1 < result.roots.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static void appendNode(StringBuilder json, ExportNode node, int indent) {
        String padding = " ".repeat(indent);
        json.append(padding).append("{\n");
        json.append(padding).append("  \"label\": ").append(quote(node.label)).append(",\n");
        json.append(padding).append("  \"type\": ").append(quote(node.type)).append(",\n");
        json.append(padding).append("  \"section\": ").append(quote(node.section)).append(",\n");
        json.append(padding).append("  \"kind\": ").append(quote(node.kind)).append(",\n");
        json.append(padding).append("  \"properties\": ");
        appendStringMap(json, node.properties, indent + 2);
        json.append(",\n");
        json.append(padding).append("  \"children\": [");
        if (!node.children.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < node.children.size(); index++) {
                appendNode(json, node.children.get(index), indent + 4);
                if (index + 1 < node.children.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append(padding).append("  ");
        }
        json.append("]\n").append(padding).append('}');
    }

    private static void appendStringMap(StringBuilder json, Map<String, String> values, int indent) {
        if (values.isEmpty()) {
            json.append("{}");
            return;
        }
        String padding = " ".repeat(indent);
        json.append("{\n");
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            json.append(padding).append("  ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
            if (++index < values.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append(padding).append('}');
    }

    private static void appendStringIntegerMap(StringBuilder json, Map<String, Integer> values, int indent) {
        if (values.isEmpty()) {
            json.append("{}");
            return;
        }
        String padding = " ".repeat(indent);
        json.append("{\n");
        int index = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            json.append(padding).append("  ").append(quote(entry.getKey())).append(": ").append(entry.getValue());
            if (++index < values.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append(padding).append('}');
    }

    private static String toMarkdown(ExportResult result) {
        StringBuilder markdown = new StringBuilder(8192);
        markdown.append("# RTC Pending Changes Export\n\n");
        markdown.append("Generated: ").append(result.generatedAt).append("\n\n");
        markdown.append("## Summary\n\n");
        markdown.append("- Total nodes: ").append(result.nodeCount).append('\n');
        for (Map.Entry<String, Integer> entry : result.nodesBySection.entrySet()) {
            markdown.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        markdown.append("- Truncated: ").append(result.truncated).append("\n\n");
        markdown.append("## Git-style Patch\n\n");
        markdown.append("- File: [rtc-pending-changes.patch](rtc-pending-changes.patch)\n");
        markdown.append("- Exported changes: ").append(result.patchSummary.getExportedChanges()).append('\n');
        markdown.append("- Binary changes: ").append(result.patchSummary.getBinaryChanges()).append('\n');
        markdown.append("- Skipped changes: ").append(result.patchSummary.getSkippedChanges()).append('\n');
        markdown.append("- Patch bytes: ").append(result.patchSummary.getPatchBytes()).append("\n\n");
        if (!result.patchSummary.getSkippedReasons().isEmpty()) {
            markdown.append("### Skipped Diff Details\n\n");
            for (String reason : result.patchSummary.getSkippedReasons()) {
                markdown.append("- ").append(reason.replace("\n", " ")).append('\n');
            }
            markdown.append('\n');
        }
        markdown.append("## Pending Changes\n\n");
        for (ExportNode root : result.roots) {
            appendMarkdownNode(markdown, root, 0);
        }
        return markdown.toString();
    }

    private static void appendMarkdownNode(StringBuilder markdown, ExportNode node, int depth) {
        markdown.append("  ".repeat(depth)).append("- [").append(node.section).append("] ")
                .append(node.label.replace("\n", " ")).append(" _(").append(node.kind).append(")_\n");
        for (Map.Entry<String, String> property : node.properties.entrySet()) {
            markdown.append("  ".repeat(depth + 1)).append("- ").append(property.getKey()).append(": `")
                    .append(property.getValue().replace("`", "\\`").replace("\n", " ")).append("`\n");
        }
        for (ExportNode child : node.children) {
            appendMarkdownNode(markdown, child, depth + 1);
        }
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class ExportResult {
        private final String generatedAt;
        private final List<ExportNode> roots = new ArrayList<>();
        private final Map<String, Integer> nodesBySection = new LinkedHashMap<>();
        private final List<Object> patchElements = new ArrayList<>();
        private final Set<Object> loadingParents =
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        private GitPatchExporter.PatchSummary patchSummary = new GitPatchExporter.PatchSummary();
        private int nodeCount;
        private boolean truncated;

        private ExportResult(String generatedAt) {
            this.generatedAt = generatedAt;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public int getExportedPatchCount() {
            return patchSummary.getExportedChanges();
        }

        boolean hasPendingModelData() {
            return !loadingParents.isEmpty();
        }

        List<Object> getLoadingParents() {
            return new ArrayList<>(loadingParents);
        }
    }

    private static final class ExportNode {
        private final String label;
        private final String type;
        private final String section;
        private final String kind;
        private final Map<String, String> properties;
        private final List<ExportNode> children = new ArrayList<>();

        private ExportNode(String label, String type, String section, String kind, Map<String, String> properties) {
            this.label = label;
            this.type = type;
            this.section = section;
            this.kind = kind;
            this.properties = properties;
        }
    }
}
