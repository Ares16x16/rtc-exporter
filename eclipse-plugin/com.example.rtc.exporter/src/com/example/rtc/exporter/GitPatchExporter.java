/*
 * Copyright (c) 2026 Ares16x16.
 * SPDX-License-Identifier: EPL-2.0
 */

package com.example.rtc.exporter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;

import com.ibm.team.filesystem.client.ILocalChange;
import com.ibm.team.filesystem.client.IShareable;
import com.ibm.team.filesystem.common.IFileContent;
import com.ibm.team.filesystem.common.IFileItem;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.scm.client.SCMPlatform;
import com.ibm.team.scm.common.IChange;
import com.ibm.team.scm.common.IVersionable;
import com.ibm.team.scm.common.IVersionableHandle;

final class GitPatchExporter {
    private static final String UNRESOLVED_ITEM_INTERFACE =
            "com.ibm.team.filesystem.rcp.core.internal.changes.model.IUnresolvedItem";
    private static final String REMOTE_CHANGE_INTERFACE =
            "com.ibm.team.filesystem.rcp.core.internal.changes.model.IRemoteChangeSummary";
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_LINES_PER_FILE = 100000;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_PATCH_BYTES = 5 * 1024 * 1024;

    private GitPatchExporter() {
    }

    static boolean supports(Object element) {
        return element != null
                && (implementsInterface(element.getClass(), UNRESOLVED_ITEM_INTERFACE)
                        || implementsInterface(element.getClass(), REMOTE_CHANGE_INTERFACE));
    }

    static PatchSummary write(List<Object> elements, Path patchFile, IProgressMonitor monitor) throws IOException {
        PatchSummary summary = new PatchSummary();
        StringBuilder patch = new StringBuilder();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Set<String> keys = new LinkedHashSet<>();

        for (Object element : elements) {
            checkCanceled(monitor);
            if (element == null || !visited.add(element)) {
                continue;
            }
            try {
                if (implementsInterface(element.getClass(), UNRESOLVED_ITEM_INTERFACE)) {
                    appendLocalChanges(element, patch, keys, summary, monitor);
                } else if (implementsInterface(element.getClass(), REMOTE_CHANGE_INTERFACE)) {
                    appendRemoteChange(element, patch, keys, summary, monitor);
                }
            } catch (OperationCanceledException error) {
                throw error;
            } catch (Exception error) {
                summary.skip("Could not read " + displayName(element) + ": " + message(error));
            }
        }

        summary.patchBytes = patch.toString().getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(patchFile, patch, StandardCharsets.UTF_8);
        return summary;
    }

    private static void appendLocalChanges(
            Object element,
            StringBuilder patch,
            Set<String> keys,
            PatchSummary summary,
            IProgressMonitor monitor) throws Exception {
        Object changes = invokeInterface(element, UNRESOLVED_ITEM_INTERFACE, "getLocalChanges");
        ITeamRepository repository = localRepository(element);
        for (Object value : values(changes)) {
            checkCanceled(monitor);
            if (!(value instanceof ILocalChange)) {
                continue;
            }
            ILocalChange change = (ILocalChange) value;
            if (!change.isType(ILocalChange.CONTENT)
                    && !change.isType(ILocalChange.ADDITION)
                    && !change.isType(ILocalChange.DELETION)
                    && !change.isType(ILocalChange.MOVE_TO)) {
                continue;
            }

            String oldPath = path(change.getOriginalPath(), change.getPath());
            String newPath = path(change.getResultingPath(), change.getPath());
            boolean added = change.isType(ILocalChange.ADDITION);
            boolean deleted = change.isType(ILocalChange.DELETION);
            if (added) {
                oldPath = newPath;
            } else if (deleted) {
                newPath = oldPath;
            }
            String key = "local|" + oldPath + "|" + newPath + "|" + change.getType();
            key += "|" + text(change.getTarget());
            if (!keys.add(key)) {
                continue;
            }

            ContentData before = added ? ContentData.missing() : repositoryContent(repository, change.getTarget(), monitor);
            ContentData after = deleted ? ContentData.missing() : localContent(change.getShareable(), monitor);
            appendCandidate(oldPath, newPath, before, after, added, deleted, patch, summary, monitor);
        }
    }

    private static void appendRemoteChange(
            Object element,
            StringBuilder patch,
            Set<String> keys,
            PatchSummary summary,
            IProgressMonitor monitor) throws Exception {
        Object rawChange = invokeInterface(element, REMOTE_CHANGE_INTERFACE, "getChange");
        if (!(rawChange instanceof IChange)) {
            summary.skip("RTC did not expose file states for " + displayName(element));
            return;
        }
        IChange change = (IChange) rawChange;
        boolean added = change.kind() == IChange.ADD;
        boolean deleted = change.kind() == IChange.DELETE;
        String oldPath = text(invokeInterface(element, REMOTE_CHANGE_INTERFACE, "getBeforePath"));
        String newPath = text(invokeInterface(element, REMOTE_CHANGE_INTERFACE, "getAfterPath"));
        if (oldPath.isBlank()) {
            oldPath = newPath;
        }
        if (newPath.isBlank()) {
            newPath = oldPath;
        }
        String key = "remote|" + oldPath + "|" + newPath + "|" + change.kind();
        key += "|" + text(change.beforeState()) + "|" + text(change.afterState());
        if (!keys.add(key)) {
            return;
        }

        ITeamRepository repository = remoteRepository(element);
        ContentData before = added ? ContentData.missing() : repositoryContent(repository, change.beforeState(), monitor);
        ContentData after = deleted ? ContentData.missing() : repositoryContent(repository, change.afterState(), monitor);
        appendCandidate(oldPath, newPath, before, after, added, deleted, patch, summary, monitor);
    }

    private static void appendCandidate(
            String oldPath,
            String newPath,
            ContentData before,
            ContentData after,
            boolean added,
            boolean deleted,
            StringBuilder patch,
            PatchSummary summary,
            IProgressMonitor monitor) {
        checkCanceled(monitor);
        String label = added ? newPath : oldPath;
        if (before.oversized || after.oversized) {
            summary.skip(label + " exceeds the 1 MB per-side limit");
            return;
        }
        if (before.error != null || after.error != null) {
            summary.skip(label + ": " + (before.error != null ? before.error : after.error));
            return;
        }

        String encoding = before.encoding != null ? before.encoding : after.encoding;
        String candidate;
        boolean binary = false;
        if (before.binary || after.binary
                || (!allowsZeroBytes(encoding) && (containsZeroByte(before.bytes) || containsZeroByte(after.bytes)))) {
            candidate = binaryPatch(oldPath, newPath, added, deleted);
            binary = true;
        } else {
            try {
                String oldText = decode(before.bytes, encoding);
                String newText = decode(after.bytes, encoding);
                candidate = unifiedPatch(oldPath, newPath, oldText, newText, added, deleted, monitor);
            } catch (CharacterCodingException error) {
                candidate = binaryPatch(oldPath, newPath, added, deleted);
                binary = true;
            } catch (IllegalArgumentException error) {
                summary.skip(label + ": " + error.getMessage());
                return;
            }
        }

        if (candidate.isEmpty()) {
            return;
        }
        int candidateBytes = candidate.getBytes(StandardCharsets.UTF_8).length;
        if (summary.patchBytes + candidateBytes > MAX_PATCH_BYTES) {
            summary.skip(label + " would exceed the 5 MB total patch limit");
            return;
        }
        patch.append(candidate);
        summary.patchBytes += candidateBytes;
        summary.exportedChanges++;
        if (binary) {
            summary.binaryChanges++;
        }
    }

    static String unifiedPatchForTest(
            String oldPath, String newPath, String oldText, String newText, boolean added, boolean deleted) {
        return unifiedPatch(oldPath, newPath, oldText, newText, added, deleted, null);
    }

    private static String unifiedPatch(
            String oldPath,
            String newPath,
            String oldText,
            String newText,
            boolean added,
            boolean deleted,
            IProgressMonitor monitor) {
        TextLines oldLines = TextLines.parse(oldText);
        TextLines newLines = TextLines.parse(newText);
        if (oldLines.lines.size() > MAX_LINES_PER_FILE || newLines.lines.size() > MAX_LINES_PER_FILE) {
            throw new IllegalArgumentException("file exceeds the 100,000-line limit");
        }

        RangeDifference[] differences = RangeDifferencer.findDifferences(
                monitor, new LineComparator(oldLines.lines), new LineComparator(newLines.lines));
        boolean renamed = !normalizePath(oldPath).equals(normalizePath(newPath));
        if (differences.length == 0 && !renamed) {
            return "";
        }

        String oldName = normalizePath(oldPath);
        String newName = normalizePath(newPath);
        StringBuilder result = new StringBuilder();
        result.append("diff --git a/").append(oldName).append(" b/").append(newName).append('\n');
        if (added) {
            result.append("new file mode 100644\n");
        } else if (deleted) {
            result.append("deleted file mode 100644\n");
        } else if (renamed) {
            result.append("rename from ").append(oldName).append('\n');
            result.append("rename to ").append(newName).append('\n');
        }
        if (differences.length == 0) {
            return result.toString();
        }
        result.append("--- ").append(added ? "/dev/null" : "a/" + oldName).append('\n');
        result.append("+++ ").append(deleted ? "/dev/null" : "b/" + newName).append('\n');

        for (Hunk hunk : hunks(differences, oldLines.lines.size(), newLines.lines.size())) {
            result.append("@@ -").append(range(hunk.oldStart, hunk.oldEnd - hunk.oldStart));
            result.append(" +").append(range(hunk.newStart, hunk.newEnd - hunk.newStart)).append(" @@\n");
            appendHunk(result, hunk, differences, oldLines, newLines);
        }
        return result.toString();
    }

    private static List<Hunk> hunks(RangeDifference[] differences, int oldSize, int newSize) {
        List<Hunk> hunks = new ArrayList<>();
        for (RangeDifference difference : differences) {
            Hunk next = new Hunk(
                    Math.max(0, difference.leftStart() - CONTEXT_LINES),
                    Math.min(oldSize, difference.leftEnd() + CONTEXT_LINES),
                    Math.max(0, difference.rightStart() - CONTEXT_LINES),
                    Math.min(newSize, difference.rightEnd() + CONTEXT_LINES));
            if (!hunks.isEmpty() && hunks.get(hunks.size() - 1).overlaps(next)) {
                hunks.get(hunks.size() - 1).merge(next);
            } else {
                hunks.add(next);
            }
        }
        return hunks;
    }

    private static void appendHunk(
            StringBuilder result,
            Hunk hunk,
            RangeDifference[] differences,
            TextLines oldLines,
            TextLines newLines) {
        int oldIndex = hunk.oldStart;
        int newIndex = hunk.newStart;
        for (RangeDifference difference : differences) {
            if (difference.leftEnd() < hunk.oldStart || difference.leftStart() > hunk.oldEnd
                    || difference.rightEnd() < hunk.newStart || difference.rightStart() > hunk.newEnd) {
                continue;
            }
            while (oldIndex < difference.leftStart() && newIndex < difference.rightStart()) {
                appendLine(result, ' ', oldLines, oldIndex++);
                newIndex++;
            }
            while (oldIndex < difference.leftEnd()) {
                appendLine(result, '-', oldLines, oldIndex++);
            }
            while (newIndex < difference.rightEnd()) {
                appendLine(result, '+', newLines, newIndex++);
            }
        }
        while (oldIndex < hunk.oldEnd && newIndex < hunk.newEnd) {
            appendLine(result, ' ', oldLines, oldIndex++);
            newIndex++;
        }
    }

    private static void appendLine(StringBuilder result, char prefix, TextLines text, int index) {
        result.append(prefix).append(text.lines.get(index)).append('\n');
        if (index + 1 == text.lines.size() && !text.finalNewline) {
            result.append("\\ No newline at end of file\n");
        }
    }

    private static String binaryPatch(String oldPath, String newPath, boolean added, boolean deleted) {
        String oldName = normalizePath(oldPath);
        String newName = normalizePath(newPath);
        String left = added ? "/dev/null" : "a/" + oldName;
        String right = deleted ? "/dev/null" : "b/" + newName;
        return "diff --git a/" + oldName + " b/" + newName + "\n"
                + "Binary files " + left + " and " + right + " differ\n";
    }

    private static ContentData localContent(IShareable shareable, IProgressMonitor monitor) throws IOException {
        if (shareable == null || shareable.getFullPath() == null) {
            return ContentData.error("local file path is unavailable");
        }
        Path file = Paths.get(shareable.getFullPath().toOSString());
        if (!Files.isRegularFile(file)) {
            return ContentData.error("local file is unavailable");
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            return ContentData.oversized();
        }
        boolean binary = false;
        try {
            binary = isKnownBinaryContentType(shareable.getContentType(monitor));
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (Exception ignored) {
            // Fall back to bounded content inspection.
        }
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                return ContentData.oversized();
            }
            return new ContentData(bytes, null, binary, false, null);
        }
    }

    private static ContentData repositoryContent(
            ITeamRepository repository, IVersionableHandle handle, IProgressMonitor monitor) throws Exception {
        if (repository == null || handle == null) {
            return ContentData.error("repository file state is unavailable");
        }
        IVersionable state = SCMPlatform.getWorkspaceManager(repository)
                .versionableManager().fetchCompleteState(handle, monitor);
        if (!(state instanceof IFileItem)) {
            return ContentData.error("changed item is not a file");
        }
        IFileItem file = (IFileItem) state;
        IFileContent content = file.getContent();
        if (content == null) {
            return ContentData.error("repository content is unavailable");
        }
        if (content.getRawLength() > MAX_FILE_BYTES || content.getSize() > MAX_FILE_BYTES) {
            return ContentData.oversized();
        }
        try (InputStream stream = SCMPlatform.getContentManager(repository)
                .retrieveContentStream(handle, content, monitor)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                return ContentData.oversized();
            }
            return new ContentData(bytes, content.getCharacterEncoding(),
                    isKnownBinaryContentType(file.getContentType()), false, null);
        }
    }

    private static ITeamRepository localRepository(Object element) throws Exception {
        Object folder = invokeInterface(element, UNRESOLVED_ITEM_INTERFACE, "getFolder");
        Object model = invoke(folder, "getModel");
        Object repository = invoke(model, "localTeamRepository");
        return repository instanceof ITeamRepository ? (ITeamRepository) repository : null;
    }

    private static ITeamRepository remoteRepository(Object element) throws Exception {
        Object activity = invokeInterface(element, REMOTE_CHANGE_INTERFACE, "getActivity");
        Object source = invoke(activity, "getActivitySource");
        Object repository = invoke(source, "getTeamRepository");
        return repository instanceof ITeamRepository ? (ITeamRepository) repository : null;
    }

    private static Object invokeInterface(Object target, String interfaceName, String methodName) throws Exception {
        if (target == null) {
            return null;
        }
        Class<?> contract = findInterface(target.getClass(), interfaceName);
        if (contract == null) {
            return null;
        }
        return contract.getMethod(methodName).invoke(target);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        if (target == null) {
            return null;
        }
        for (Class<?> contract : allInterfaces(target.getClass())) {
            try {
                Method method = contract.getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the next public interface.
            }
        }
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static boolean implementsInterface(Class<?> type, String name) {
        return findInterface(type, name) != null;
    }

    private static Class<?> findInterface(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        for (Class<?> contract : type.getInterfaces()) {
            if (contract.getName().equals(name)) {
                return contract;
            }
            Class<?> nested = findInterface(contract, name);
            if (nested != null) {
                return nested;
            }
        }
        return findInterface(type.getSuperclass(), name);
    }

    private static List<Class<?>> allInterfaces(Class<?> type) {
        List<Class<?>> result = new ArrayList<>();
        collectInterfaces(type, result);
        return result;
    }

    private static void collectInterfaces(Class<?> type, List<Class<?>> result) {
        if (type == null) {
            return;
        }
        for (Class<?> contract : type.getInterfaces()) {
            if (!result.contains(contract)) {
                result.add(contract);
                collectInterfaces(contract, result);
            }
        }
        collectInterfaces(type.getSuperclass(), result);
    }

    private static List<Object> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?>) {
            List<Object> result = new ArrayList<>();
            for (Object element : (Iterable<?>) value) {
                result.add(element);
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(Array.get(value, index));
            }
            return result;
        }
        return List.of(value);
    }

    private static String decode(byte[] bytes, String encoding) throws CharacterCodingException {
        if (bytes.length == 0) {
            return "";
        }
        List<Charset> charsets = new ArrayList<>();
        if (encoding != null && !encoding.isBlank()) {
            charsets.add(Charset.forName(encoding));
        } else {
            charsets.add(StandardCharsets.UTF_8);
            if (!Charset.defaultCharset().equals(StandardCharsets.UTF_8)) {
                charsets.add(Charset.defaultCharset());
            }
        }
        CharacterCodingException last = null;
        for (Charset charset : charsets) {
            try {
                CharBuffer decoded = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes));
                String value = decoded.toString();
                return value.startsWith("\uFEFF") ? value.substring(1) : value;
            } catch (CharacterCodingException error) {
                last = error;
            }
        }
        throw last;
    }

    private static boolean containsZeroByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowsZeroBytes(String encoding) {
        String normalized = encoding == null ? "" : encoding.toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("UTF-16") || normalized.startsWith("UTF-32");
    }

    private static boolean isKnownBinaryContentType(String contentType) {
        return contentType != null && !contentType.isBlank()
                && !IFileItem.CONTENT_TYPE_TEXT.equals(contentType)
                && !IFileItem.CONTENT_TYPE_XML.equals(contentType)
                && !IFileItem.CONTENT_TYPE_UNKNOWN.equals(contentType);
    }

    private static String path(Object preferred, Object fallback) {
        String value = text(preferred);
        return value.isBlank() ? text(fallback) : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalizePath(String value) {
        String normalized = text(value).replace('\\', '/').replace('\r', '_').replace('\n', '_').replace('\t', '_');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String range(int zeroBasedStart, int length) {
        int displayStart = length == 0 ? zeroBasedStart : zeroBasedStart + 1;
        return displayStart + "," + length;
    }

    private static String displayName(Object element) {
        String value = text(element);
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }

    private static String message(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String detail = cause.getMessage();
        return cause.getClass().getSimpleName() + (detail == null ? "" : ": " + detail);
    }

    private static void checkCanceled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }

    static final class PatchSummary {
        private int exportedChanges;
        private int binaryChanges;
        private int skippedChanges;
        private int patchBytes;
        private final List<String> skippedReasons = new ArrayList<>();

        private void skip(String reason) {
            skippedChanges++;
            if (skippedReasons.size() < 100) {
                skippedReasons.add(reason);
            }
        }

        int getExportedChanges() {
            return exportedChanges;
        }

        int getBinaryChanges() {
            return binaryChanges;
        }

        int getSkippedChanges() {
            return skippedChanges;
        }

        int getPatchBytes() {
            return patchBytes;
        }

        List<String> getSkippedReasons() {
            return Collections.unmodifiableList(skippedReasons);
        }
    }

    private static final class ContentData {
        private final byte[] bytes;
        private final String encoding;
        private final boolean binary;
        private final boolean oversized;
        private final String error;

        private ContentData(byte[] bytes, String encoding, boolean binary, boolean oversized, String error) {
            this.bytes = bytes;
            this.encoding = encoding;
            this.binary = binary;
            this.oversized = oversized;
            this.error = error;
        }

        private static ContentData missing() {
            return new ContentData(new byte[0], null, false, false, null);
        }

        private static ContentData oversized() {
            return new ContentData(new byte[0], null, false, true, null);
        }

        private static ContentData error(String message) {
            return new ContentData(new byte[0], null, false, false, message);
        }
    }

    private static final class TextLines {
        private final List<String> lines;
        private final boolean finalNewline;

        private TextLines(List<String> lines, boolean finalNewline) {
            this.lines = lines;
            this.finalNewline = finalNewline;
        }

        private static TextLines parse(String value) {
            List<String> lines = new ArrayList<>();
            int start = 0;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '\r' || character == '\n') {
                    lines.add(value.substring(start, index));
                    if (character == '\r' && index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                        index++;
                    }
                    start = index + 1;
                }
            }
            boolean finalNewline = start == value.length() && !value.isEmpty();
            if (start < value.length()) {
                lines.add(value.substring(start));
            }
            return new TextLines(lines, finalNewline);
        }
    }

    private static final class LineComparator implements IRangeComparator {
        private final List<String> lines;

        private LineComparator(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public int getRangeCount() {
            return lines.size();
        }

        @Override
        public boolean rangesEqual(int thisIndex, IRangeComparator other, int otherIndex) {
            return other instanceof LineComparator
                    && lines.get(thisIndex).equals(((LineComparator) other).lines.get(otherIndex));
        }

        @Override
        public boolean skipRangeComparison(int length, int maxLength, IRangeComparator other) {
            return false;
        }
    }

    private static final class Hunk {
        private int oldStart;
        private int oldEnd;
        private int newStart;
        private int newEnd;

        private Hunk(int oldStart, int oldEnd, int newStart, int newEnd) {
            this.oldStart = oldStart;
            this.oldEnd = oldEnd;
            this.newStart = newStart;
            this.newEnd = newEnd;
        }

        private boolean overlaps(Hunk other) {
            return other.oldStart <= oldEnd && other.newStart <= newEnd;
        }

        private void merge(Hunk other) {
            oldEnd = Math.max(oldEnd, other.oldEnd);
            newEnd = Math.max(newEnd, other.newEnd);
        }
    }
}
