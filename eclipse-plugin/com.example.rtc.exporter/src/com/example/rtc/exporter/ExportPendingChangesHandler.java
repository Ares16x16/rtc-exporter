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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ibm.team.filesystem.ui.changes.views.LocalWorkspaceChangesView;

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

        LocalWorkspaceChangesView view = findPendingChangesView(page, shell);
        if (view == null) {
            return null;
        }

        view.checkActivePage();
        TreeViewer viewer = view.getActiveViewer();
        if (viewer == null || viewer.getInput() == null) {
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter",
                    "The Pending Changes view is not ready. Wait for RTC to finish refreshing, then try again.");
            return null;
        }

        DirectoryDialog dialog = new DirectoryDialog(shell);
        dialog.setText("Export RTC Pending Changes");
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

        Path output = parent.resolve("rtc-exporter-eclipse-export-" + DIRECTORY_TIMESTAMP.format(LocalDateTime.now()));
        try {
            AtomicReference<PendingChangesExporter.ExportResult> resultReference = new AtomicReference<>();
            Display display = shell.getDisplay();
            try {
                new ProgressMonitorDialog(shell).run(true, true, monitor -> {
                    monitor.beginTask("Exporting RTC metadata and file differences", IProgressMonitor.UNKNOWN);
                    try {
                        PendingChangesExporter.ExportResult result = captureWhenReady(viewer, display, monitor);
                        PendingChangesExporter.write(result, output, monitor);
                        resultReference.set(result);
                    } catch (OperationCanceledException canceled) {
                        throw new InterruptedException();
                    } catch (Exception error) {
                        throw new InvocationTargetException(error);
                    } finally {
                        monitor.done();
                    }
                });
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                String message = cause == null || cause.getMessage() == null
                        ? "The export could not be completed." : cause.getMessage();
                MessageDialog.openError(shell, "RTC Exporter", message);
                return null;
            } catch (InterruptedException canceled) {
                return null;
            }
            PendingChangesExporter.ExportResult result = resultReference.get();
            MessageDialog.openInformation(
                    shell,
                    "RTC Exporter Complete",
                    "Exported " + result.getNodeCount() + " Pending Changes nodes and "
                            + result.getExportedPatchCount() + " file differences to:\n\n" + output);
        } catch (Exception error) {
            throw new ExecutionException("Could not export RTC Pending Changes", error);
        }
        return null;
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
                        "RTC is still loading incoming snapshot details. Expand Incoming in Pending Changes, wait for it to finish, then export again.");
            }
            monitor.subTask("Waiting for incoming snapshot details from RTC");
            Thread.sleep(MODEL_LOAD_POLL_MILLIS);
            refresh = false;
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
}
