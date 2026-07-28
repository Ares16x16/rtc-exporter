/*
 * Copyright (c) 2026 Ares16x16.
 * SPDX-License-Identifier: EPL-2.0
 */

package com.example.rtc.exporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.CheckedTreeSelectionDialog;
import org.eclipse.ui.dialogs.ListSelectionDialog;

import com.ibm.team.filesystem.ui.configuration.IHistoryEntry;

final class ExportSelectionDialogs {
    private ExportSelectionDialogs() {
    }

    static PendingChangesExporter.ExportResult choosePendingChanges(
            Shell shell, PendingChangesExporter.ExportResult source) {
        CheckedTreeSelectionDialog dialog = new CheckedTreeSelectionDialog(
                shell, pendingLabels(), pendingContent());
        dialog.setTitle("Select Pending Changes to Export");
        dialog.setMessage("Check change sets, unresolved items, or individual files. Checking a parent includes its children.");
        dialog.setContainerMode(true);
        dialog.setInput(source);
        dialog.setExpandedElements(source.getRoots().toArray());
        dialog.setInitialElementSelections(PendingChangesExporter.allNodes(source));
        dialog.setSize(760, 520);
        if (dialog.open() != Window.OK) {
            return null;
        }
        Set<PendingChangesExporter.ExportNode> selected =
                Collections.newSetFromMap(new IdentityHashMap<PendingChangesExporter.ExportNode, Boolean>());
        for (Object value : dialog.getResult()) {
            if (value instanceof PendingChangesExporter.ExportNode) {
                selected.add((PendingChangesExporter.ExportNode) value);
            }
        }
        if (selected.isEmpty()) {
            MessageDialog.openInformation(shell, "RTC Exporter", "Select at least one Pending Changes item to export.");
            return null;
        }
        return PendingChangesExporter.filter(source, selected);
    }

    static List<IHistoryEntry> chooseHistoryEntries(Shell shell, List<IHistoryEntry> entries) {
        ListSelectionDialog dialog = new ListSelectionDialog(
                shell,
                entries,
                ArrayContentProvider.getInstance(),
                new LabelProvider() {
                    @Override
                    public String getText(Object element) {
                        return element instanceof IHistoryEntry
                                ? HistoryExporter.entryLabel((IHistoryEntry) element)
                                : String.valueOf(element);
                    }
                },
                "Select all loaded history entries or only the change sets to export.");
        dialog.setTitle("Select RTC History Change Sets");
        dialog.setInitialElementSelections(entries);
        if (dialog.open() != Window.OK) {
            return null;
        }
        List<IHistoryEntry> selected = new ArrayList<>();
        for (Object value : dialog.getResult()) {
            if (value instanceof IHistoryEntry) {
                selected.add((IHistoryEntry) value);
            }
        }
        if (selected.isEmpty()) {
            MessageDialog.openInformation(shell, "RTC Exporter", "Select at least one history change set to export.");
            return null;
        }
        return selected;
    }

    static HistoryExporter.HistoryResult chooseHistoryFiles(
            Shell shell, HistoryExporter.HistoryResult source) {
        CheckedTreeSelectionDialog dialog = new CheckedTreeSelectionDialog(
                shell, historyLabels(), historyContent());
        dialog.setTitle("Select History Files to Export");
        dialog.setMessage("Uncheck any files that should be omitted. Checking a change set includes every file below it.");
        dialog.setContainerMode(true);
        dialog.setInput(source);
        dialog.setExpandedElements(source.getChangeSets().toArray());
        dialog.setInitialElementSelections(HistoryExporter.allNodes(source));
        dialog.setSize(760, 520);
        if (dialog.open() != Window.OK) {
            return null;
        }
        Set<Object> selected = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Collections.addAll(selected, dialog.getResult());
        if (selected.isEmpty()) {
            MessageDialog.openInformation(shell, "RTC Exporter", "Select at least one history change set or file to export.");
            return null;
        }
        return HistoryExporter.filter(source, selected);
    }

    private static ILabelProvider pendingLabels() {
        return new LabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof PendingChangesExporter.ExportNode
                        ? ((PendingChangesExporter.ExportNode) element).getLabel()
                        : String.valueOf(element);
            }
        };
    }

    private static ITreeContentProvider pendingContent() {
        return new ITreeContentProvider() {
            @Override
            public Object[] getElements(Object inputElement) {
                return inputElement instanceof PendingChangesExporter.ExportResult
                        ? ((PendingChangesExporter.ExportResult) inputElement).getRoots().toArray()
                        : new Object[0];
            }

            @Override
            public Object[] getChildren(Object parentElement) {
                return parentElement instanceof PendingChangesExporter.ExportNode
                        ? ((PendingChangesExporter.ExportNode) parentElement).getChildren().toArray()
                        : new Object[0];
            }

            @Override
            public Object getParent(Object element) {
                return null;
            }

            @Override
            public boolean hasChildren(Object element) {
                return getChildren(element).length > 0;
            }

            @Override
            public void dispose() {
            }

            @Override
            public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            }
        };
    }

    private static ILabelProvider historyLabels() {
        return new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof HistoryExporter.HistoryChangeSet) {
                    return ((HistoryExporter.HistoryChangeSet) element).getLabel();
                }
                if (element instanceof HistoryExporter.HistoryFile) {
                    return ((HistoryExporter.HistoryFile) element).getLabel();
                }
                return String.valueOf(element);
            }
        };
    }

    private static ITreeContentProvider historyContent() {
        return new ITreeContentProvider() {
            @Override
            public Object[] getElements(Object inputElement) {
                return inputElement instanceof HistoryExporter.HistoryResult
                        ? ((HistoryExporter.HistoryResult) inputElement).getChangeSets().toArray()
                        : new Object[0];
            }

            @Override
            public Object[] getChildren(Object parentElement) {
                return parentElement instanceof HistoryExporter.HistoryChangeSet
                        ? ((HistoryExporter.HistoryChangeSet) parentElement).getFiles().toArray()
                        : new Object[0];
            }

            @Override
            public Object getParent(Object element) {
                return null;
            }

            @Override
            public boolean hasChildren(Object element) {
                return getChildren(element).length > 0;
            }

            @Override
            public void dispose() {
            }

            @Override
            public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            }
        };
    }
}
