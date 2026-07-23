# Copyright (c) 2026 Ares16x16.
# SPDX-License-Identifier: EPL-2.0

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "rtc_exporter.py"
SPEC = importlib.util.spec_from_file_location("rtc_exporter", MODULE_PATH)
rtc = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(rtc)


SAMPLE = {
    "workspaces": [
        {
            "name": "Workspace1",
            "uuid": "ws-uuid",
            "url": "https://secret.example/ccm",
            "userId": "secret-user",
            "flow-target": {"name": "Stream1", "uuid": "stream-uuid", "type": "STREAM", "url": "secret"},
            "components": [
                {
                    "name": "Comp1",
                    "uuid": "comp-uuid",
                    "baseline": {"id": 2, "name": "Baseline 2", "uuid": "base-uuid"},
                    "unresolved": [
                        {
                            "path": "/Comp1/src/local.txt",
                            "uuid": "local-uuid",
                            "state": {"add": False, "delete": False, "content_change": True},
                        }
                    ],
                    "outgoing-changes": [
                        {
                            "uuid": "out-uuid",
                            "alias": 101,
                            "comment": "Fix output",
                            "creator": {"name": "Developer"},
                            "changes": [
                                {"path": "/Comp1/src/a.py", "state": {"content_change": True}},
                                {"path": "/Comp1/src/new.py", "state": {"add": True}},
                            ],
                            "work-items": [{"id": 42, "summary": "Defect"}],
                        }
                    ],
                    "incoming": {
                        "change-sets": [
                            {
                                "uuid": "in-uuid",
                                "comment": "Team update",
                                "changes": [{"path": "/Comp1/src/a.py", "state": {"delete": True}}],
                            }
                        ]
                    },
                }
            ],
        }
    ]
}


class ExporterTests(unittest.TestCase):
    def test_normalizes_pending_change_categories(self):
        result = rtc.normalize_status(SAMPLE, Path("C:/sandbox"))
        component = result["workspaces"][0]["components"][0]
        self.assertEqual(["modify-content"], component["localChanges"][0]["operations"])
        self.assertEqual("Fix output", component["outgoingChangeSets"][0]["comment"])
        self.assertEqual(["add"], component["outgoingChangeSets"][0]["changes"][1]["operations"])
        self.assertEqual("Team update", component["incomingChangeSets"][0]["comment"])
        self.assertEqual(3, result["summary"]["changedFiles"])

    def test_single_changeset_object_is_not_treated_as_its_file_list(self):
        sample = json.loads(json.dumps(SAMPLE))
        component = sample["workspaces"][0]["components"][0]
        component["outgoing-changes"] = component["outgoing-changes"][0]
        result = rtc.normalize_status(sample)
        change_sets = result["workspaces"][0]["components"][0]["outgoingChangeSets"]
        self.assertEqual("out-uuid", change_sets[0]["uuid"])
        self.assertEqual(2, len(change_sets[0]["changes"]))

    def test_redacts_private_fields_recursively(self):
        result = rtc.redact(SAMPLE)
        workspace = result["workspaces"][0]
        self.assertNotIn("url", workspace)
        self.assertNotIn("userId", workspace)
        self.assertNotIn("url", workspace["flow-target"])

    def test_extract_json_tolerates_cli_prefix(self):
        result = rtc.extract_json("RTC notice\n" + json.dumps(SAMPLE) + "\n")
        self.assertEqual("Workspace1", result["workspaces"][0]["name"])

    def test_builds_eclipse_cli_command(self):
        command = rtc.eclipse_cli_command(Path("C:/Eclipse/eclipsec.exe"))
        self.assertEqual("eclipsec.exe", Path(command[0]).name)
        self.assertEqual("@noDefault", command[command.index("-data") + 1])
        self.assertEqual("com.ibm.team.rtc.cli.infrastructure.id1", command[-1])

    def test_shadow_mode_defaults_to_auto(self):
        args = rtc.parser().parse_args(["collect"])
        self.assertEqual("auto", args.shadow)

    def test_rejects_output_inside_sandbox(self):
        with tempfile.TemporaryDirectory(dir='C:/tmp') as directory:
            sandbox = Path(directory)
            (sandbox / ".jazz5").mkdir()
            with self.assertRaises(rtc.ExportError):
                rtc.ensure_output_outside_sandbox(sandbox / "export", sandbox)

    def test_parse_command_writes_json_and_markdown(self):
        with tempfile.TemporaryDirectory(dir='C:/tmp') as directory:
            root = Path(directory)
            source = root / "status.json"
            output = root / "output"
            source.write_text(json.dumps(SAMPLE), encoding="utf-8")
            exit_code = rtc.main(["parse", str(source), "--output", str(output)])
            self.assertEqual(0, exit_code)
            self.assertTrue((output / "rtc-export.json").is_file())
            self.assertIn("Outgoing change set: Fix output", (output / "rtc-export.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
