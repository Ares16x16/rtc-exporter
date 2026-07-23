#!/usr/bin/env python3
# Copyright (c) 2026 Ares16x16.
# SPDX-License-Identifier: EPL-2.0

"""Export IBM RTC/EWM SCM pending changes into review-ready artifacts."""

from __future__ import annotations

import argparse
import copy
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


FORMAT_NAME = "rtc-export"
FORMAT_VERSION = 1
PRIVATE_KEYS = {"url", "repository-uri", "repository_uri", "userid", "user-id", "username", "password"}


class ExportError(RuntimeError):
    pass


class SandboxLockedError(ExportError):
    pass


class AuthenticationRequiredError(ExportError):
    pass


def first_value(value: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in value:
            return value[name]
    return None


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def identifier(value: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key in ("name", "uuid", "alias", "id"):
        found = first_value(value, key, key.replace("_", "-"))
        if found not in (None, ""):
            result[key] = found
    return result


def operation_from_state(state: Any) -> list[str]:
    if isinstance(state, str):
        return [state]
    if not isinstance(state, dict):
        return []
    mapping = (
        ("add", "add"),
        ("delete", "delete"),
        ("move", "move"),
        ("rename", "rename"),
        ("content_change", "modify-content"),
        ("content-change", "modify-content"),
        ("property_change", "modify-properties"),
        ("property-change", "modify-properties"),
    )
    operations: list[str] = []
    for source, target in mapping:
        if state.get(source) is True and target not in operations:
            operations.append(target)
    return operations


def normalize_change(value: dict[str, Any]) -> dict[str, Any]:
    state = first_value(value, "state", "status")
    result: dict[str, Any] = {
        "path": first_value(value, "path", "name", "item-path", "item_path") or "<unknown>",
        "operations": operation_from_state(state),
    }
    old_path = first_value(value, "old-path", "old_path", "source-path", "source_path")
    if old_path:
        result["oldPath"] = old_path
    for key in ("uuid", "alias"):
        found = value.get(key)
        if found not in (None, ""):
            result[key] = found
    if isinstance(state, dict):
        result["state"] = state
    return result


def list_from_keys(value: dict[str, Any], keys: Iterable[str]) -> list[Any]:
    for key in keys:
        if key in value:
            found = value[key]
            if isinstance(found, dict):
                nested = first_value(found, "change-sets", "change_sets", "changesets")
                if nested is not None:
                    return as_list(nested)
            return as_list(found)
    return []


def normalize_work_item(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {"id": value}
    result: dict[str, Any] = {}
    for source, target in (("id", "id"), ("uuid", "uuid"), ("summary", "summary"), ("title", "summary")):
        if source in value and target not in result:
            result[target] = value[source]
    return result


def normalize_change_set(value: dict[str, Any], direction: str) -> dict[str, Any]:
    result = identifier(value)
    result["direction"] = direction
    comment = first_value(value, "comment", "description", "summary")
    if comment is not None:
        result["comment"] = comment
    creator = first_value(value, "creator", "author", "userId", "user-id")
    if isinstance(creator, dict):
        creator = first_value(creator, "name", "userId", "user-id", "id")
    if creator:
        result["creator"] = creator
    created = first_value(value, "created", "creation-date", "creation_date", "date")
    if created:
        result["created"] = created
    completed = first_value(value, "completed", "is-complete", "is_complete")
    if completed is not None:
        result["completed"] = completed
    changes = list_from_keys(value, ("changes", "items", "changed-items", "changed_items"))
    result["changes"] = [normalize_change(item) for item in changes if isinstance(item, dict)]
    work_items = list_from_keys(value, ("work-items", "work_items", "workitems"))
    if work_items:
        result["workItems"] = [normalize_work_item(item) for item in work_items]
    return result


def normalize_status(raw: dict[str, Any], sandbox: Path | None = None) -> dict[str, Any]:
    workspaces: list[dict[str, Any]] = []
    all_files: set[str] = set()
    totals = {"localChanges": 0, "outgoingChangeSets": 0, "incomingChangeSets": 0, "changedFiles": 0}

    for ws_value in as_list(first_value(raw, "workspaces", "workspace")):
        if not isinstance(ws_value, dict):
            continue
        ws = identifier(ws_value)
        flow = first_value(ws_value, "flow-target", "flow_target", "flowTarget")
        if isinstance(flow, dict):
            ws["flowTarget"] = {**identifier(flow), **({"type": flow["type"]} if "type" in flow else {})}
        components: list[dict[str, Any]] = []
        for component_value in as_list(first_value(ws_value, "components", "component")):
            if not isinstance(component_value, dict):
                continue
            component = identifier(component_value)
            baseline = component_value.get("baseline")
            if isinstance(baseline, dict):
                component["baseline"] = identifier(baseline)

            local_values = list_from_keys(component_value, ("unresolved", "local-changes", "local_changes"))
            local = [normalize_change(item) for item in local_values if isinstance(item, dict)]
            outgoing_values = list_from_keys(
                component_value, ("outgoing-changes", "outgoing_changes", "outgoing", "outgoing-change-sets")
            )
            incoming_values = list_from_keys(
                component_value, ("incoming-changes", "incoming_changes", "incoming", "incoming-change-sets")
            )
            outgoing = [normalize_change_set(item, "outgoing") for item in outgoing_values if isinstance(item, dict)]
            incoming = [normalize_change_set(item, "incoming") for item in incoming_values if isinstance(item, dict)]

            component["localChanges"] = local
            component["outgoingChangeSets"] = outgoing
            component["incomingChangeSets"] = incoming
            components.append(component)

            totals["localChanges"] += len(local)
            totals["outgoingChangeSets"] += len(outgoing)
            totals["incomingChangeSets"] += len(incoming)
            for change in local:
                all_files.add(str(change["path"]))
            for change_set in outgoing + incoming:
                for change in change_set["changes"]:
                    all_files.add(str(change["path"]))
        ws["components"] = components
        workspaces.append(ws)

    totals["changedFiles"] = len(all_files)
    sandbox_info: dict[str, Any] = {}
    if sandbox:
        sandbox_info = {"name": sandbox.name, "rtcMetadata": ".jazz5"}
    return {
        "format": FORMAT_NAME,
        "formatVersion": FORMAT_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sandbox": sandbox_info,
        "summary": totals,
        "workspaces": workspaces,
    }


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: redact(item) for key, item in value.items() if key.lower() not in PRIVATE_KEYS}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def extract_json(text: str) -> dict[str, Any]:
    decoder = json.JSONDecoder()
    for index, char in enumerate(text):
        if char != "{":
            continue
        try:
            value, _ = decoder.raw_decode(text[index:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            return value
    raise ExportError("RTC output did not contain a JSON object")


def find_sandbox(start: Path) -> Path:
    current = start.resolve()
    if current.is_file():
        current = current.parent
    for candidate in (current, *current.parents):
        if (candidate / ".jazz5").is_dir():
            return candidate
    raise ExportError(f"No RTC .jazz5 directory found at or above: {start}")


def eclipse_cli_command(executable: Path) -> list[str]:
    return [
        str(executable.resolve()),
        "-nosplash",
        "-data",
        "@noDefault",
        "-application",
        "com.ibm.team.rtc.cli.infrastructure.id1",
    ]


def has_eclipse_rtc_cli(executable: Path) -> bool:
    bundles = executable.parent / "configuration" / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"
    try:
        content = bundles.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False
    return "com.ibm.team.rtc.cli.infrastructure" in content and "com.ibm.team.filesystem.cli.client" in content


def find_scm(explicit: str | None) -> list[str]:
    candidates: list[str] = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("RTC_SCM"):
        candidates.append(os.environ["RTC_SCM"])
    for name in ("lscm", "scm"):
        found = shutil.which(name)
        if found:
            candidates.append(found)
    program_files = [os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)")]
    suffixes = (
        Path("IBM/TeamConcert/scmtools/eclipse/lscm.bat"),
        Path("IBM/TeamConcert/scmtools/eclipse/scm.exe"),
        Path("IBM/JazzTeamServer/scmtools/eclipse/lscm.bat"),
    )
    for root in filter(None, program_files):
        candidates.extend(str(Path(root) / suffix) for suffix in suffixes)
    for candidate in candidates:
        path = Path(candidate).expanduser()
        if path.is_file():
            if path.name.lower() == "eclipsec.exe" and has_eclipse_rtc_cli(path):
                return eclipse_cli_command(path)
            return [str(path.resolve())]
        found = shutil.which(candidate)
        if found:
            return [found]

    eclipse_root = Path.home() / "eclipse"
    if eclipse_root.is_dir():
        eclipse_candidates = sorted(
            eclipse_root.glob("**/eclipsec.exe"), key=lambda path: path.stat().st_mtime, reverse=True
        )
        for executable in eclipse_candidates:
            if has_eclipse_rtc_cli(executable):
                return eclipse_cli_command(executable)
    raise ExportError(
        "RTC SCM CLI not found. Pass --scm PATH, set RTC_SCM, or install the EWM SCM CLI feature in Eclipse."
    )


def run_command(command: list[str], cwd: Path) -> str:
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    except OSError as error:
        raise ExportError(f"Could not run RTC SCM CLI: {error}") from error
    if completed.returncode != 0:
        stdout = completed.stdout.strip()
        stderr = completed.stderr.strip()
        detail = "\n".join(part for part in (stdout, stderr) if part) or "no error text"
        if "Another RCP application is running in this sandbox" in detail:
            raise SandboxLockedError("RTC sandbox is locked by a running Eclipse instance")
        if "Password (" in detail:
            raise AuthenticationRequiredError(
                "RTC CLI login is required. Run 'python rtc_exporter.py login --repository URL --username USER' "
                "once, then rerun collection. The password is prompted securely in PowerShell."
            )
        raise ExportError(f"RTC command failed with exit code {completed.returncode}: {detail}")
    return completed.stdout


@contextmanager
def shadow_sandbox(source: Path) -> Iterable[Path]:
    temporary = tempfile.TemporaryDirectory(prefix="rtc-exporter-shadow-")
    shadow = Path(temporary.name)
    links: list[Path] = []
    try:
        source_metadata = source / ".jazz5"
        shadow_metadata = shadow / ".jazz5"
        shutil.copytree(source_metadata, shadow_metadata, ignore=shutil.ignore_patterns(".jazzlock"))

        for metadata_component in source_metadata.iterdir():
            if not metadata_component.is_dir() or metadata_component.name.startswith("."):
                continue
            source_component = source / metadata_component.name
            if not source_component.is_dir():
                continue
            link = shadow / metadata_component.name
            if os.name == "nt":
                created = subprocess.run(
                    ["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(source_component)],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    check=False,
                )
                if created.returncode != 0:
                    raise ExportError(f"Could not create shadow link for {metadata_component.name}: {created.stderr}")
            else:
                link.symlink_to(source_component, target_is_directory=True)
            links.append(link)
        yield shadow
    finally:
        for link in reversed(links):
            try:
                if os.name == "nt":
                    link.rmdir()
                else:
                    link.unlink()
            except FileNotFoundError:
                pass
        temporary.cleanup()


def safe_name(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-.")
    return cleaned[:80] or "unknown"


def iter_components(export: dict[str, Any]) -> Iterable[dict[str, Any]]:
    for workspace in export["workspaces"]:
        yield from workspace["components"]


def collect_diffs(
    export: dict[str, Any], scm: list[str], sandbox: Path, output: Path, max_bytes: int, max_total_bytes: int
) -> None:
    diff_dir = output / "diffs"
    diff_dir.mkdir(parents=True, exist_ok=True)
    used = 0
    seen_change_sets: set[str] = set()

    def save_diff(command: list[str], name: str) -> dict[str, Any]:
        nonlocal used
        if used >= max_total_bytes:
            return {"omitted": True, "reason": "total diff byte limit reached"}
        try:
            content = run_command(command, sandbox)
        except ExportError as error:
            return {"error": str(error)}
        encoded = content.encode("utf-8")
        allowed = min(max_bytes, max_total_bytes - used)
        truncated = len(encoded) > allowed
        if truncated:
            encoded = encoded[:allowed]
            content = encoded.decode("utf-8", errors="ignore") + "\n... diff truncated by rtc-exporter ...\n"
        path = diff_dir / name
        path.write_text(content, encoding="utf-8", newline="\n")
        used += len(encoded)
        return {"file": path.relative_to(output).as_posix(), "truncated": truncated, "bytes": len(encoded)}

    for component in iter_components(export):
        for index, change in enumerate(component["localChanges"], start=1):
            rtc_path = str(change["path"]).lstrip("/\\").replace("/", os.sep)
            local_path = sandbox / rtc_path
            name = f"local-{safe_name(component.get('name', 'component'))}-{index}-{safe_name(local_path.name)}.diff"
            change["diff"] = save_diff([*scm, "diff", "-d", str(sandbox), "file", str(local_path)], name)

        for direction in ("outgoingChangeSets", "incomingChangeSets"):
            for change_set in component[direction]:
                selector = str(change_set.get("uuid") or change_set.get("alias") or "")
                if not selector:
                    change_set["diff"] = {"omitted": True, "reason": "change set has no UUID or alias"}
                    continue
                key = f"{direction}:{selector}"
                if key in seen_change_sets:
                    continue
                seen_change_sets.add(key)
                name = f"{safe_name(direction)}-{safe_name(selector)}.diff"
                change_set["diff"] = save_diff(
                    [*scm, "diff", "-d", str(sandbox), "changeset", selector], name
                )
    export["diffExport"] = {"bytesWritten": used, "perDiffLimit": max_bytes, "totalLimit": max_total_bytes}


def markdown(export: dict[str, Any]) -> str:
    summary = export["summary"]
    lines = [
        "# RTC change export",
        "",
        f"Generated: {export['generatedAt']}",
        "",
        "## Summary",
        "",
        f"- Local unresolved changes: {summary['localChanges']}",
        f"- Outgoing change sets: {summary['outgoingChangeSets']}",
        f"- Incoming change sets: {summary['incomingChangeSets']}",
        f"- Unique changed paths: {summary['changedFiles']}",
        "",
    ]
    for workspace in export["workspaces"]:
        lines.extend([f"## Workspace: {workspace.get('name', '<unnamed>')}", ""])
        flow = workspace.get("flowTarget")
        if flow:
            lines.extend([f"Flow target: {flow.get('name', '<unnamed>')} ({flow.get('type', 'unknown')})", ""])
        for component in workspace["components"]:
            lines.extend([f"### Component: {component.get('name', '<unnamed>')}", ""])
            if component["localChanges"]:
                lines.extend(["Local changes not checked in:", ""])
                for change in component["localChanges"]:
                    ops = ", ".join(change["operations"]) or "changed"
                    diff = change.get("diff", {}).get("file")
                    suffix = f" — [diff]({diff})" if diff else ""
                    lines.append(f"- `{change['path']}` ({ops}){suffix}")
                lines.append("")
            for key, title in (("outgoingChangeSets", "Outgoing"), ("incomingChangeSets", "Incoming")):
                for change_set in component[key]:
                    selector = change_set.get("uuid") or change_set.get("alias") or "unknown"
                    comment = change_set.get("comment") or "No comment"
                    lines.extend([f"#### {title} change set: {comment}", "", f"ID: `{selector}`", ""])
                    if change_set.get("creator"):
                        lines.extend([f"Creator: {change_set['creator']}", ""])
                    diff = change_set.get("diff", {}).get("file")
                    if diff:
                        lines.extend([f"Patch: [diff]({diff})", ""])
                    for change in change_set["changes"]:
                        ops = ", ".join(change["operations"]) or "changed"
                        lines.append(f"- `{change['path']}` ({ops})")
                    if not change_set["changes"]:
                        lines.append("- No expanded file data returned by RTC")
                    lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def ensure_output_outside_sandbox(output: Path, sandbox: Path) -> None:
    output_resolved = output.resolve()
    sandbox_resolved = sandbox.resolve()
    if output_resolved == sandbox_resolved or sandbox_resolved in output_resolved.parents:
        raise ExportError("Output must be outside the RTC sandbox so the export cannot become a workspace change")


def write_export(export: dict[str, Any], output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / "rtc-export.json").write_text(
        json.dumps(export, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    (output / "rtc-export.md").write_text(markdown(export), encoding="utf-8", newline="\n")


def default_output() -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return Path(tempfile.gettempdir()) / f"rtc-export-{stamp}"


def command_collect(args: argparse.Namespace) -> int:
    sandbox = find_sandbox(Path(args.sandbox))
    output = Path(args.output).resolve() if args.output else default_output().resolve()
    ensure_output_outside_sandbox(output, sandbox)
    scm = find_scm(args.scm)

    def collect_from(cli_sandbox: Path, shadowed: bool) -> int:
        command = [
            *scm,
            "-u",
            "y",
            "-a",
            "y",
            "show",
            "status",
            "-j",
            "-C",
            "-I",
            "-d",
            str(cli_sandbox),
        ]
        if args.no_refresh:
            command.append("-N")
        raw = extract_json(run_command(command, cli_sandbox))
        export = normalize_status(raw, sandbox)
        export["collection"] = {
            "localRefresh": not args.no_refresh,
            "diffsIncluded": args.include_diffs,
            "shadowSandbox": shadowed,
        }
        if args.include_raw_status:
            export["rawStatus"] = redact(copy.deepcopy(raw))
        if args.include_diffs:
            collect_diffs(export, scm, cli_sandbox, output, args.max_diff_bytes, args.max_total_diff_bytes)
        write_export(export, output)
        print(output)
        return 0

    if args.shadow == "always":
        with shadow_sandbox(sandbox) as shadow:
            return collect_from(shadow, True)
    if args.shadow == "never":
        return collect_from(sandbox, False)
    try:
        return collect_from(sandbox, False)
    except SandboxLockedError:
        print("RTC sandbox is in use by Eclipse; collecting through a read-only shadow sandbox.", file=sys.stderr)
        with shadow_sandbox(sandbox) as shadow:
            return collect_from(shadow, True)


def command_login(args: argparse.Namespace) -> int:
    scm = find_scm(args.scm)
    command = [*scm, "login", "-r", args.repository, "-u", args.username, "-n", args.nickname]
    if not args.no_cache:
        command.append("-c")
    try:
        return subprocess.call(command)
    except OSError as error:
        raise ExportError(f"Could not run RTC SCM CLI: {error}") from error


def command_parse(args: argparse.Namespace) -> int:
    input_path = Path(args.input).resolve()
    output = Path(args.output).resolve()
    raw = extract_json(input_path.read_text(encoding="utf-8-sig"))
    export = normalize_status(raw)
    if args.include_raw_status:
        export["rawStatus"] = redact(copy.deepcopy(raw))
    write_export(export, output)
    print(output)
    return 0


def command_doctor(args: argparse.Namespace) -> int:
    problems: list[str] = []
    try:
        sandbox = find_sandbox(Path(args.sandbox))
        print(f"RTC sandbox: {sandbox}")
    except ExportError as error:
        problems.append(str(error))
    try:
        scm = find_scm(args.scm)
        print(f"RTC SCM CLI: {subprocess.list2cmdline(scm)}")
    except ExportError as error:
        problems.append(str(error))
    for problem in problems:
        print(f"Problem: {problem}", file=sys.stderr)
    return 1 if problems else 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        prog="rtc-exporter",
        description="Export IBM RTC/EWM SCM pending changes as stable JSON and Markdown for review.",
    )
    subparsers = result.add_subparsers(dest="command", required=True)

    collect = subparsers.add_parser("collect", help="Collect pending changes from an RTC sandbox")
    collect.add_argument("--sandbox", default=".", help="Path inside the RTC sandbox (default: current directory)")
    collect.add_argument("--output", help="Output directory; defaults to the system temporary directory")
    collect.add_argument("--scm", help="Optional path to lscm, scm, or an RTC-enabled eclipsec.exe")
    collect.add_argument("--no-refresh", action="store_true", help="Skip local disk scan (local edits may be absent)")
    collect.add_argument("--include-diffs", action="store_true", help="Export bounded unified diffs")
    collect.add_argument(
        "--shadow",
        choices=("auto", "always", "never"),
        default="auto",
        help="Use a temporary metadata copy when Eclipse locks the sandbox (default: auto)",
    )
    collect.add_argument("--max-diff-bytes", type=int, default=250_000)
    collect.add_argument("--max-total-diff-bytes", type=int, default=2_000_000)
    collect.add_argument("--include-raw-status", action="store_true", help="Include sanitized RTC JSON for diagnostics")
    collect.set_defaults(function=command_collect)

    login = subparsers.add_parser("login", help="Cache an RTC CLI login using an interactive password prompt")
    login.add_argument("--repository", required=True, help="RTC repository URL, usually ending in /ccm/")
    login.add_argument("--username", required=True, help="RTC user ID")
    login.add_argument("--nickname", default="rtc-exporter", help="Local repository nickname (default: rtc-exporter)")
    login.add_argument("--no-cache", action="store_true", help="Do not cache the login after this command")
    login.add_argument("--scm", help="Optional path to lscm, scm, or an RTC-enabled eclipsec.exe")
    login.set_defaults(function=command_login)

    parse = subparsers.add_parser("parse", help="Normalize JSON previously captured with RTC SCM")
    parse.add_argument("input", help="RTC status JSON file")
    parse.add_argument("--output", required=True, help="Output directory")
    parse.add_argument("--include-raw-status", action="store_true")
    parse.set_defaults(function=command_parse)

    doctor = subparsers.add_parser("doctor", help="Check sandbox and RTC SCM CLI discovery")
    doctor.add_argument("--sandbox", default=".")
    doctor.add_argument("--scm")
    doctor.set_defaults(function=command_doctor)
    return result


def main(argv: list[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        return args.function(args)
    except (ExportError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
