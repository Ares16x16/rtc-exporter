# RTC Exporter for Eclipse

This plug-in exports the authenticated Pending Changes model and Git-style file differences directly from the running [IBM Engineering Workflow Management](https://www.ibm.com/products/ibm-engineering-workflow-management) Eclipse client. It does not start another RTC process, request a second login, or modify the RTC sandbox.

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

For local testing, extract `rtc-exporter-p2-2.0.1.20260723.zip` and add the
extracted directory as a local software site.

The repository contains only:

- `com.example.rtc.exporter`
- `io.github.ares16x16.rtc.exporter.feature`

It does not contain IBM or Eclipse bundles.

## Install with dropins

1. Close Eclipse.
2. Extract `rtc-exporter-dropins-2.0.1.20260723.zip` into the Eclipse installation directory. The archive creates:

   `dropins/rtc-exporter/plugins/com.example.rtc.exporter_2.0.1.20260723.jar`

3. Start Eclipse once with `eclipse.exe -clean`.

Alternatively, copy the standalone JAR into `<ECLIPSE_HOME>/dropins/rtc-exporter/plugins/`.

Dropins installation does not invoke P2 and cannot update unrelated Eclipse components.

## Uninstall

Close Eclipse, remove `dropins/rtc-exporter`, then start Eclipse once with `eclipse.exe -clean`.

## Use

1. Open or refresh **Pending Changes**.
2. Click the **Export RTC Status...** toolbar button, or use **RTC Exporter → Export Pending Changes…**.
3. Choose a directory outside the RTC sandbox.

The plug-in creates a timestamped folder containing:

- `rtc-pending-changes.json`
- `rtc-pending-changes.md`
- `rtc-pending-changes.patch`

The export preserves the Pending Changes hierarchy and captures local unresolved, conflict, outgoing, incoming, suspended, workspace, component, change-set, work-item, and changed-item nodes available in the view. It also records safe scalar model properties such as path, comment, state, identifiers, and completion flags when the installed RTC model exposes them.

If an incoming snapshot or baseline contains RTC's temporary `Pending...` child, the plug-in expands that node and waits up to 30 seconds for the real children. It stops with an actionable error if RTC does not finish, rather than exporting the placeholder as an incoming change.

The patch contains unified text differences for local unresolved files and incoming/outgoing file changes when RTC exposes both file states. Binary files are identified without embedding their contents. Files larger than 1 MB per side, text files over 100,000 lines, and changes that would push the combined patch over 5 MB are skipped and explained in the JSON and Markdown summaries. Because patches can contain source code or secrets, review the export before sharing it.

## Compatibility

This build targets EWM 7.1 with a Java 17-compatible Eclipse runtime. The integration uses the public `LocalWorkspaceChangesView#getActiveViewer()` entry point, public RTC file/content APIs, and reflection only for version-dependent Pending Changes model interfaces.

Because those version-dependent model interfaces are not stable public API,
test each new EWM client release before declaring it supported.

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
