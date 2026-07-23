# RTC Exporter

RTC Exporter captures [IBM Engineering Workflow Management](https://www.ibm.com/products/ibm-engineering-workflow-management)
(formerly Rational Team Concert) SCM pending changes as JSON, Markdown, and
Git-style unified patches.

The project includes an Eclipse plug-in for an authenticated, running RTC client and a command-line exporter for automation or offline processing.

RTC Exporter is an independent project. It is not affiliated with, sponsored
by, or endorsed by IBM. The distributed plug-in does not contain IBM software;
an appropriately licensed IBM Engineering Workflow Management client must be
installed separately.

## Install the Eclipse plug-in

### Eclipse Marketplace or p2 repository

The preferred public distribution is the RTC Exporter p2 repository. Install
the official IBM Engineering Workflow Management Eclipse client first, then
use **Help → Eclipse Marketplace** or add
`https://ares16x16.github.io/rtc-exporter/p2/` through
**Help → Install New Software**.

The RTC Exporter p2 repository contains only this project's plug-in and
feature. It does not mirror or redistribute IBM or Eclipse bundles.

### Dropins package

1. Close Eclipse.
2. Extract `rtc-exporter-dropins-2.0.1.20260723.zip` into the Eclipse installation directory.
3. Confirm this file exists:

   `dropins/rtc-exporter/plugins/com.example.rtc.exporter_2.0.1.20260723.jar`

4. Start Eclipse once with `eclipse.exe -clean`.

After Eclipse starts, open or refresh **Pending Changes**, then use **Export RTC Status...** on the main toolbar or **RTC Exporter → Export Pending Changes...** from the top menu.

Version 2 also writes `rtc-pending-changes.patch` using the file states already exposed to the running RTC client. It does not launch the SCM command-line client or start a second RTC process.

When an incoming snapshot or baseline is still loading, the exporter expands it and waits up to 30 seconds for RTC to replace its `Pending...` placeholder. If RTC does not finish, the export stops with instructions instead of writing incomplete incoming data.

See the [Eclipse plug-in guide](eclipse-plugin/README.md) for complete installation, usage, uninstall, compatibility, and build instructions.

## Command-line exporter

The command-line exporter uses the supported IBM SCM interface. It does **not** parse or modify `.jazz5`, check in, accept, deliver, suspend, or otherwise change repository state.

## What it exports

- local unresolved file changes (files changed on disk but not checked in)
- outgoing change sets (checked in to the repository workspace but not delivered)
- incoming change sets (in the flow target but not accepted)
- component, baseline, workspace, flow-target, change-set, work-item, and changed-file metadata returned by RTC
- optional unified diffs, bounded by per-diff and total byte limits

The output package contains:

- `rtc-export.json`: normalized machine-readable data
- `rtc-export.md`: compact human-readable summary
- `diffs/*.diff`: optional patch files

## Requirements

- Python 3.10 or newer
- IBM RTC/EWM SCM CLI (`lscm` or `scm`) compatible with your server
- an existing RTC login, or another authentication method already configured for the CLI

The analyzer automatically discovers `lscm`/`scm` on `PATH`, conventional IBM installations, and RTC-enabled Eclipse installations managed by P2. Use `--scm` only to override discovery.

## Usage on Windows

First check discovery:

```powershell
python C:/path/to/rtc-exporter/rtc_exporter.py doctor `
  --sandbox "C:/path/to/rtc-sandbox"
```

Export metadata and changed-file lists:

```powershell
python C:/path/to/rtc-exporter/rtc_exporter.py collect `
  --sandbox "C:/path/to/rtc-sandbox" `
  --output "C:\tmp\my-rtc-export"
```

Include diffs for local files and incoming/outgoing change sets:

```powershell
python C:/path/to/rtc-exporter/rtc_exporter.py collect `
  --sandbox "C:/path/to/rtc-sandbox" `
  --output "C:\tmp\my-rtc-export" `
  --include-diffs
```

If automatic discovery selects the wrong installation, provide `--scm` with the real `lscm.bat`, `scm.exe`, or RTC-enabled `eclipsec.exe` path.

### One-time CLI login

Eclipse and the SCM command-line client do not share encrypted login sessions. Cache a CLI login once; the password is entered directly into RTC's PowerShell prompt and is never passed to or stored by this project:

```powershell
python C:/path/to/rtc-exporter/rtc_exporter.py login `
  --repository "https://your-rtc-server:9443/ccm/" `
  --username "your-user-id"
```

### Collecting while Eclipse is open

RTC exclusively locks a loaded sandbox. In the default `--shadow auto` mode, the analyzer detects that lock, copies only `.jazz5` metadata to a temporary directory, and links the temporary component folders to the live source folders. RTC scans and updates only this disposable shadow; your actual workspace remains read-only. The export records `collection.shadowSandbox: true` when this fallback was used.

Use `--shadow always` to skip the initial lock check, or `--shadow never` to require direct sandbox access.

The output directory is required to be outside the RTC sandbox. This prevents generated export artifacts from appearing as local RTC changes.

For a very large sandbox, `--no-refresh` makes collection faster but omits new local disk edits that RTC has not already detected. Outgoing and incoming repository change sets are still collected.

## Offline/diagnostic parse mode

If RTC is available on another machine, capture its JSON there:

```powershell
lscm -u y -a y show status -j -C -I -d "C:\path\to\rtc-sandbox" > status.json
```

Then normalize it without an RTC installation:

```powershell
python C:/path/to/rtc-exporter/rtc_exporter.py parse status.json --output C:/tmp/parsed-rtc-export
```

## Privacy and limits

- Repository URLs and login/user ID fields are not included in the normalized output.
- Raw RTC JSON is excluded by default. `--include-raw-status` adds a recursively sanitized copy for compatibility diagnostics.
- Diffs can contain source code, secrets, or personal data. Review the package before sending it to an external service.
- Diff output defaults to 250 KB per diff and 2 MB total. Adjust with `--max-diff-bytes` and `--max-total-diff-bytes`.

## RTC concept mapping

| RTC location | Export field | Meaning |
|---|---|---|
| Pending Changes → Unresolved | `localChanges` | Disk changes not yet checked in |
| Pending Changes → Outgoing | `outgoingChangeSets` | Repository-workspace changes not yet delivered to the flow target |
| Pending Changes → Incoming | `incomingChangeSets` | Flow-target changes not yet accepted into the repository workspace |
| Change set → Changes | `changes` | File/folder paths and operations belonging to that change set |

## Verification

Run the built-in unit tests:

```powershell
python -m unittest discover -s C:/path/to/rtc-exporter/tests -v
```

The tests use synthetic RTC JSON and do not access an RTC sandbox or server.

## License and notices

RTC Exporter is available under the [Eclipse Public License 2.0](LICENSE).
Source code for released binaries is available in this repository.

See:

- [Notices and IBM trademark attribution](NOTICE.md)
- [Privacy statement](PRIVACY.md)
- [Security policy](SECURITY.md)

IBM, Rational, Rational Team Concert, and Engineering Workflow Management are
trademarks or registered trademarks of International Business Machines
Corporation in the United States, other countries, or both.
