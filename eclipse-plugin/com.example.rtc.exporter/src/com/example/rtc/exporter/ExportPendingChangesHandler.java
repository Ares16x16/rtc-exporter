/*
 * Copyright (c) 2026 Ares16x16.
 * SPDX-License-Identifier: EPL-2.0
 */

package com.example.rtc.exporter;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.team.ui.history.IHistoryPage;
import org.eclipse.team.ui.history.IHistoryView;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.Page;

import com.ibm.team.filesystem.ui.changes.views.LocalWorkspaceChangesView;
import com.ibm.team.filesystem.ui.configuration.IHistoryEntry;

public final class ExportPendingChangesHandler extends AbstractHandler {
    private static final String PENDING_CHANGES_VIEW_ID =
            "com.ibm.team.filesystem.ui.changes.views.LocalWorkspaceChangesView";
    private static final DateTimeFormatter DIRECTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long MODEL_LOAD_TIMEOUT_MILLIS = 30000;
    private static final long MODEL_LOAD_POLL_MILLIS = 250;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        Shell shell = window.getShell();
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            MessageDialog.openError(shell, "RTC Exporter", "No active Eclipse workbench page was found.");
            return null;
        }

        int mode = new MessageDialog(
                shell,
                "RTC Exporter",
                null,
                "Choose what to export. History uses the change sets currently loaded in Eclipse's History view.",
                MessageDialog.QUESTION,
                new String[] {"Pending Changes", "History", "Cancel"},
                0).open();
        if (mode == 0) {
            exportPendingChanges(page, shell);
        } else if (mode == 1) {
            exportHistory(page, shell);
        }
        return null;
    }

    private void exportPendingChanges(IWorkbenchPage page, Shell shell) throws ExecutionException {
        LocalWorkspaceChangesView view = findPendingChangesView(page, shell);
        if (view == null) {
            return;
        }
        view.checkActivePage();
        TreeViewer viewer = view.getActiveViewer();
        if (viewer == null || viewer.getInput() == null) {
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter",
                    "The Pending Changes view is not ready. Wait for EWM to finish refreshing, then try again.");
            return;
        }

        AtomicReference<PendingChangesExporter.ExportResult> resultReference = new AtomicReference<>();
        Display display = shell.getDisplay();
        if (!runProgress(shell, "Reading RTC Pending Changes", monitor -> {
            PendingChangesExporter.ExportResult result = captureWhenReady(viewer, display, monitor);
            resultReference.set(result);
        })) {
            return;
        }

        PendingChangesExporter.ExportResult selected =
                ExportSelectionDialogs.choosePendingChanges(shell, resultReference.get());
        if (selected == null) {
            return;
        }
        Path output = chooseOutputDirectory(shell);
        if (output == null) {
            return;
        }
        if (!runProgress(shell, "Writing RTC Pending Changes export", monitor ->
                PendingChangesExporter.write(selected, output, monitor))) {
            return;
        }
        MessageDialog.openInformation(
                shell,
                "RTC Exporter Complete",
                "Exported " + selected.getNodeCount() + " selected Pending Changes nodes and "
                        + selected.getExportedPatchCount() + " file differences to:\n\n" + output);
    }

    private void exportHistory(IWorkbenchPage page, Shell shell) throws ExecutionException {
        List<IHistoryEntry> loadedEntries = loadedHistoryEntries(page, shell);
        if (loadedEntries == null || loadedEntries.isEmpty()) {
            return;
        }
        List<IHistoryEntry> selectedEntries =
                ExportSelectionDialogs.chooseHistoryEntries(shell, loadedEntries);
        if (selectedEntries == null) {
            return;
        }

        AtomicReference<HistoryExporter.HistoryResult> resultReference = new AtomicReference<>();
        if (!runProgress(shell, "Reading selected RTC history change sets", monitor ->
                resultReference.set(HistoryExporter.prepare(selectedEntries, monitor)))) {
            return;
        }
        HistoryExporter.HistoryResult prepared = resultReference.get();
        if (prepared.getChangeSets().isEmpty()) {
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter",
                    "The selected History rows do not contain exportable change sets.");
            return;
        }
        HistoryExporter.HistoryResult selected =
                ExportSelectionDialogs.chooseHistoryFiles(shell, prepared);
        if (selected == null) {
            return;
        }
        Path output = chooseOutputDirectory(shell);
        if (output == null) {
            return;
        }
        if (!runProgress(shell, "Writing RTC history export", monitor ->
                HistoryExporter.write(selected, output, monitor))) {
            return;
        }
        MessageDialog.openInformation(
                shell,
                "RTC Exporter Complete",
                "Exported " + selected.getChangeSets().size() + " history change sets, "
                        + selected.getFileCount() + " selected files, and "
                        + selected.getExportedPatchCount() + " file differences to:\n\n" + output);
    }

    private Path chooseOutputDirectory(Shell shell) {
        DirectoryDialog dialog = new DirectoryDialog(shell);
        dialog.setText("Export RTC Data");
        dialog.setMessage("Choose a parent directory. A timestamped export folder will be created inside it.");
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        if (temporaryDirectory != null && Files.isDirectory(Paths.get(temporaryDirectory))) {
            dialog.setFilterPath(temporaryDirectory);
        }
        String selected = dialog.open();
        if (selected == null) {
            return null;
        }
        Path parent = Paths.get(selected).toAbsolutePath().normalize();
        if (isInsideRtcSandbox(parent)) {
            MessageDialog.openError(
                    shell,
                    "RTC Exporter",
                    "Choose a directory outside the RTC sandbox so generated files do not become pending changes.");
            return null;
        }
        return parent.resolve("rtc-exporter-eclipse-export-" + DIRECTORY_TIMESTAMP.format(LocalDateTime.now()));
    }

    private boolean runProgress(Shell shell, String task, ProgressTask operation) throws ExecutionException {
        try {
            new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                monitor.beginTask(task, IProgressMonitor.UNKNOWN);
                try {
                    operation.run(monitor);
                } catch (OperationCanceledException canceled) {
                    throw new InterruptedException();
                } catch (Exception error) {
                    throw new InvocationTargetException(error);
                } finally {
                    monitor.done();
                }
            });
            return true;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            String message = cause == null || cause.getMessage() == null
                    ? "The export could not be completed." : cause.getMessage();
            MessageDialog.openError(shell, "RTC Exporter", message);
            return false;
        } catch (InterruptedException canceled) {
            return false;
        } catch (RuntimeException error) {
            throw new ExecutionException("Could not export RTC data", error);
        }
    }

    private PendingChangesExporter.ExportResult captureWhenReady(
            TreeViewer viewer, Display display, IProgressMonitor monitor) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MODEL_LOAD_TIMEOUT_MILLIS;
        boolean refresh = true;
        while (true) {
            if (monitor.isCanceled()) {
                throw new OperationCanceledException();
            }
            AtomicReference<PendingChangesExporter.ExportResult> resultReference = new AtomicReference<>();
            AtomicReference<RuntimeException> failureReference = new AtomicReference<>();
            boolean refreshThisAttempt = refresh;
            display.syncExec(() -> {
                try {
                    if (viewer.getControl().isDisposed()) {
                        throw new IllegalStateException("RTC Pending Changes was closed during export");
                    }
                    if (refreshThisAttempt) {
                        viewer.refresh();
                    }
                    PendingChangesExporter.ExportResult result = PendingChangesExporter.capture(viewer);
                    for (Object parent : result.getLoadingParents()) {
                        viewer.expandToLevel(parent, 1);
                    }
                    resultReference.set(result);
                } catch (RuntimeException error) {
                    failureReference.set(error);
                }
            });
            if (failureReference.get() != null) {
                throw failureReference.get();
            }
            PendingChangesExporter.ExportResult result = resultReference.get();
            if (result != null && !result.hasPendingModelData()) {
                return result;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "EWM is still loading incoming snapshot details. Expand Incoming in Pending Changes, wait for it to finish, then export again.");
            }
            monitor.subTask("Waiting for incoming snapshot details from EWM");
            Thread.sleep(MODEL_LOAD_POLL_MILLIS);
            refresh = false;
        }
    }

    private List<IHistoryEntry> loadedHistoryEntries(IWorkbenchPage page, Shell shell) {
        IViewPart part = page.findView(IHistoryView.VIEW_ID);
        if (!(part instanceof IHistoryView)) {
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter",
                    "Open EWM history first: select a component, folder, or file, choose Show History, then run the exporter again.");
            return null;
        }
        IHistoryPage historyPage = ((IHistoryView) part).getHistoryPage();
        if (!(historyPage instanceof Page)) {
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter",
                    "The active History page is not an EWM history page. Open Show History for an EWM item and try again.");
            return null;
        }
        Control control = ((Page) historyPage).getControl();
        if (control == null || control.isDisposed()) {
            MessageDialog.openInformation(shell, "RTC Exporter", "The EWM History view is not ready yet.");
            return null;
        }
        List<IHistoryEntry> entries = new ArrayList<>();
        Set<IHistoryEntry> visited = Collections.newSetFromMap(new IdentityHashMap<IHistoryEntry, Boolean>());
        collectHistoryEntries(control, entries, visited);
        entries.removeIf(entry -> entry.getChangeSet() == null || entry.getChangeSet().getChangeSet() == null);
        if (entries.isEmpty()) {
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter",
                    "No EWM change sets are currently loaded in the History view. Refresh that view and try again.");
            return null;
        }
        return entries;
    }

    private void collectHistoryEntries(Control control, List<IHistoryEntry> result, Set<IHistoryEntry> visited) {
        if (control instanceof Table) {
            for (TableItem item : ((Table) control).getItems()) {
                Object value = item.getData();
                if (value instanceof IHistoryEntry && visited.add((IHistoryEntry) value)) {
                    result.add((IHistoryEntry) value);
                }
            }
        }
        if (control instanceof Composite) {
            for (Control child : ((Composite) control).getChildren()) {
                collectHistoryEntries(child, result, visited);
            }
        }
    }

    private LocalWorkspaceChangesView findPendingChangesView(IWorkbenchPage page, Shell shell) {
        IViewPart part = page.findView(PENDING_CHANGES_VIEW_ID);
        if (part == null) {
            try {
                part = page.showView(PENDING_CHANGES_VIEW_ID);
            } catch (PartInitException error) {
                MessageDialog.openError(shell, "RTC Exporter", "Could not open RTC Pending Changes: " + error.getMessage());
                return null;
            }
        }
        if (!(part instanceof LocalWorkspaceChangesView)) {
            MessageDialog.openError(
                    shell,
                    "RTC Exporter",
                    "The installed RTC Pending Changes view has an unsupported implementation: " + part.getClass().getName());
            return null;
        }
        return (LocalWorkspaceChangesView) part;
    }

    private boolean isInsideRtcSandbox(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isDirectory(current.resolve(".jazz5"))) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface ProgressTask {
        void run(IProgressMonitor monitor) throws Exception;
    }
}
