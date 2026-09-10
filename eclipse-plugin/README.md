# RTC Exporter for Eclipse

This plug-in exports all currently loaded Pending Changes or selected loaded change-set history with Git-style file differences directly from the running [IBM Engineering Workflow Management](https://www.ibm.com/products/ibm-engineering-workflow-management) Eclipse client. It does not start another RTC process, request a second login, or modify the RTC sandbox.

RTC Exporter is independent from IBM. The plug-in and its p2 repository do not
contain IBM software.

## Prerequisite

Install and configure the official IBM Engineering Workflow Management Eclipse
client before installing RTC Exporter. The IBM client and server remain subject
to IBM's applicable license terms.

## Install from Eclipse Marketplace or p2

The public p2 repository is the preferred installation method:

1. Open **Help → Eclipse Marketplace**, or open **Help → Install New Software**
   and add `https://ares16x16.github.io/rtc-exporter/p2/`.
2. Select **RTC Exporter**.
3. Review the license and requested dependencies, then complete the installation.
4. Restart Eclipse when prompted.

For local testing, extract `rtc-exporter-p2-3.0.2.20260910.zip` and add the
extracted directory as a local software site.

The repository contains only:

- `com.example.rtc.exporter`
- `io.github.ares16x16.rtc.exporter.feature`

It does not contain IBM or Eclipse bundles.

## Install with dropins

1. Close Eclipse.
2. Extract `rtc-exporter-dropins-3.0.2.20260910.zip` into the Eclipse installation directory. The archive creates:

   `dropins/rtc-exporter/plugins/com.example.rtc.exporter_3.0.2.20260910.jar`

3. Start Eclipse once with `eclipse.exe -clean`.

Alternatively, copy the standalone JAR into `<ECLIPSE_HOME>/dropins/rtc-exporter/plugins/`.

Dropins installation does not invoke P2 and cannot update unrelated Eclipse components.

## Uninstall

Close Eclipse, remove `dropins/rtc-exporter`, then start Eclipse once with `eclipse.exe -clean`.

## Use

Click **Export RTC...** on the main toolbar, or use **RTC Exporter → Export RTC...**. The first dialog chooses the source.

### Pending Changes

1. Open or refresh **Pending Changes**.
2. Choose **Pending Changes** in RTC Exporter.
3. The exporter captures every workspace, component, change set, unresolved item, and file currently loaded in Pending Changes; the Eclipse selection does not limit the export.
4. Choose a directory outside the RTC sandbox.

The timestamped export folder contains:

- `rtc-pending-changes.json`
- `rtc-pending-changes.md`
- `rtc-pending-changes.patch`

The JSON and Markdown retain the complete Pending Changes hierarchy. The patch contains all exportable file nodes, including local unresolved files and incoming/outgoing changes when EWM exposes both file states.

If an incoming snapshot or baseline contains EWM's temporary `Pending...` child, the plug-in expands that node and waits up to 30 seconds for the real children. It stops with an actionable error if EWM does not finish.

### History

1. In Eclipse, select the desired EWM component, folder, or file and choose **Show History**.
2. Refresh the History view and set its entry count to the range you need.
3. Run **Export RTC...** and choose **History**.
4. Select all currently loaded change sets or only particular entries.
5. Uncheck any files that should not be exported, then choose a directory outside the RTC sandbox.

The timestamped export folder contains:

- `rtc-history.json`
- `rtc-history.md`
- `rtc-history.patch`

History export reads completed change sets and their before/after repository file states from the authenticated EWM client. “All” means all rows currently loaded in the EWM History view; the plug-in does not call unstable internal server-paging services. Baseline-only rows without a change set are not offered.

### Diff limits

Binary files are identified without embedding their contents. Files larger than 1 MB per side, text files over 100,000 lines, and changes that would push one export patch over 5 MB are skipped and explained in the JSON and Markdown summaries. Patches can contain source code or secrets, so review the export before sharing it.

## Compatibility

This build targets EWM 7.1 with a Java 17-compatible Eclipse runtime. The integration uses `LocalWorkspaceChangesView#getActiveViewer()`, the public Eclipse History page, EWM history-entry/change-set and file/content interfaces, and reflection only for version-dependent Pending Changes model interfaces.

Because the Pending Changes model and some EWM history context types are version-dependent, test each new EWM client release before declaring it supported.

## Rebuild

```powershell
powershell -ExecutionPolicy Bypass -File ./build.ps1 -EclipseHome "C:/path/to/eclipse"
```

The build produces:

- a standalone plug-in JAR
- a dropins ZIP
- a compressed p2 repository ZIP
- SHA-256 checksums

The p2 publisher runs with isolated build-time configuration and workspace
directories. The generated repository is checked to ensure that it contains
only RTC Exporter plug-in and feature artifacts.
