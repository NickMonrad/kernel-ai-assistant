#!/usr/bin/env python3
"""Tests for the docs drift checker (check_docs_drift.py).

Covers pure functions (classification, rationale detection, warning building).
Does not run ``git diff`` — uses ``--changed-files`` and ``--pr-body`` arguments.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

# Import the module-under-test via its file path
HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import check_docs_drift as cdd


def _run(args: list[str]) -> tuple[str, int]:
    """Run main() with the given args, capturing stdout and exit code."""
    old_out = sys.stdout
    old_exit = sys.exit
    old_argv = sys.argv
    from io import StringIO

    captured = StringIO()
    exit_code: list[int] = []

    def _mock_exit(code: int = 0) -> None:
        exit_code.append(code)
        raise SystemExit(code)

    sys.stdout = captured
    sys.exit = _mock_exit  # type: ignore[assignment]
    sys.argv = ["check_docs_drift.py"] + args
    try:
        cdd.main()
    except SystemExit:
        pass
    finally:
        sys.stdout = old_out
        sys.exit = old_exit
        sys.argv = old_argv

    return captured.getvalue(), exit_code[0] if exit_code else 0
class ClassifyChangedFilesTest(unittest.TestCase):
        """Tests for classify_changed_files()."""



        def test_docs_only_pr_no_areas(self) -> None:
            """Pure docs changes → no behaviour-sensitive areas."""
            files = ["README.md", ".docs/agents/review-checklist.md"]
            areas = cdd.classify_changed_files(files)
            self.assertEqual(areas, [])

        def test_app_src_triggers_ux(self) -> None:
            """app/src/** changes → User-facing / UX area."""
            files = ["app/src/main/java/com/example/MainActivity.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("User-facing / UX", names)

        def test_feature_triggers_ux(self) -> None:
            """feature/** changes → User-facing / UX area."""
            files = ["feature/chat/src/main/java/ChatScreen.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("User-facing / UX", names)

        def test_voice_triggers_voice_area(self) -> None:
            """core/voice/** changes → Voice area."""
            files = ["core/voice/src/main/java/SttEngine.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Voice / STT / TTS / wake-word", names)

        def test_inference_triggers_litert(self) -> None:
            """core/inference/** changes → LiteRT area."""
            files = ["core/inference/src/main/java/LiteRtEngine.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("LiteRT / model / model availability", names)

        def test_model_availability_triggers_litert(self) -> None:
            """core/model-availability/** changes → LiteRT area."""
            files = ["core/model-availability/src/main/java/ModelCard.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("LiteRT / model / model availability", names)

        def test_scripts_evidence_triggers_test_harness(self) -> None:
            """scripts/adb_* changes → Test harness area."""
            files = ["scripts/adb_skill_test.py"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Test harness / evidence process", names)

        def test_scripts_tests_triggers_test_harness(self) -> None:
            """scripts/tests/** changes → Test harness area."""
            files = ["scripts/tests/test_something.py"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Test harness / evidence process", names)

        def test_workflow_test_triggers_test_harness(self) -> None:
            """.github/workflows/*test* changes → Test harness area."""
            files = [".github/workflows/test-runner.yml"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Test harness / evidence process", names)

        def test_architecture_triggers_arch_area(self) -> None:
            """build.gradle.kts changes → Architecture area."""
            files = ["build.gradle.kts"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Architecture / spec-relevant code", names)

        def test_gradle_settings_triggers_arch(self) -> None:
            """settings.gradle.kts changes → Architecture area."""
            files = ["settings.gradle.kts"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Architecture / spec-relevant code", names)

        def test_wasm_triggers_arch(self) -> None:
            """core/wasm/** changes → Architecture area."""
            files = ["core/wasm/src/main/java/BridgeFunctions.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Architecture / spec-relevant code", names)

        def test_skills_triggers_arch(self) -> None:
            """core/skills/** changes → Architecture area."""
            files = ["core/skills/src/main/java/SkillRegistry.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Architecture / spec-relevant code", names)

        def test_memory_triggers_arch(self) -> None:
            """core/memory/** changes → Architecture area."""
            files = ["core/memory/src/main/java/RagRepository.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Architecture / spec-relevant code", names)

        def test_unknown_path_no_areas(self) -> None:
            """Unknown/non-sensitive file → no areas detected."""
            files = [".gitignore", "README.md", ".editorconfig"]
            areas = cdd.classify_changed_files(files)
            self.assertEqual(areas, [])

        def test_permissions_android_manifest(self) -> None:
            """AndroidManifest.xml changes → Permissions area."""
            files = ["app/src/main/AndroidManifest.xml"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Permissions", names)

        def test_multiple_areas_detected(self) -> None:
            """Files touching multiple sensitive areas → all detected."""
            files = [
                "feature/chat/src/main/java/ChatScreen.kt",
                "core/voice/src/main/java/SttEngine.kt",
            ]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("User-facing / UX", names)
            self.assertIn("Voice / STT / TTS / wake-word", names)

        def test_roadmap_change_triggers_roadmap(self) -> None:
            """docs/ROADMAP.md change → ROADMAP area."""
            files = ["docs/ROADMAP.md"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("ROADMAP-relevant feature status", names)

        def test_ui_core_triggers_ux(self) -> None:
            """core/ui/** changes → User-facing / UX area."""
            files = ["core/ui/src/main/java/Theme.kt"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("User-facing / UX", names)

        def test_gradle_dir_triggers_arch(self) -> None:
            """gradle/** changes → Architecture area."""
            files = ["gradle/libs.versions.toml"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Architecture / spec-relevant code", names)

        def test_scripts_evidence_file(self) -> None:
            """scripts/*evidence* changes → Test harness area."""
            files = ["scripts/publish_test_evidence.py"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Test harness / evidence process", names)

        def test_evidence_workflow(self) -> None:
            """.github/workflows/*evidence* changes → Test harness area."""
            files = [".github/workflows/publish-test-evidence.yml"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Test harness / evidence process", names)

        def test_docs_testing_dir(self) -> None:
            """docs/testing/** changes → Test harness area."""
            files = ["docs/testing/automated-test-specification.md"]
            areas = cdd.classify_changed_files(files)
            names = [a["name"] for a in areas]
            self.assertIn("Test harness / evidence process", names)


class RelevantDocsExistTest(unittest.TestCase):
    """Tests for relevant_docs_exist()."""

    def test_ux_area_doc_updated(self) -> None:
        """UX area triggered → SPECIFICATION updated → docs exist."""
        files = ["feature/chat/Screen.kt", "docs/SPECIFICATION.md"]
        areas = cdd.classify_changed_files(files)
        self.assertTrue(cdd.relevant_docs_exist(files, areas))

    def test_ux_area_no_doc_updated(self) -> None:
        """UX area triggered → no docs updated → False."""
        files = ["feature/chat/Screen.kt"]
        areas = cdd.classify_changed_files(files)
        self.assertFalse(cdd.relevant_docs_exist(files, areas))

    def test_voice_area_doc_updated(self) -> None:
        """Voice area triggered → voice gate doc updated → docs exist."""
        files = ["core/voice/Engine.kt", ".docs/agents/review-gates-voice.md"]
        areas = cdd.classify_changed_files(files)
        self.assertTrue(cdd.relevant_docs_exist(files, areas))

    def test_test_harness_area_doc_updated(self) -> None:
        """Test harness triggered → test-evidence-workflow doc updated → docs exist."""
        files = ["scripts/adb_skill_test.py", ".docs/agents/test-evidence-workflow.md"]
        areas = cdd.classify_changed_files(files)
        self.assertTrue(cdd.relevant_docs_exist(files, areas))

    def test_arch_area_doc_updated(self) -> None:
        """Architecture triggered → SPECIFICATION updated → docs exist."""
        files = ["build.gradle.kts", "docs/SPECIFICATION.md"]
        areas = cdd.classify_changed_files(files)
        self.assertTrue(cdd.relevant_docs_exist(files, areas))


class RationaleDetectionTest(unittest.TestCase):
    """Tests for has_rationale_in_pr_body()."""

    def test_no_pr_body(self) -> None:
        """None PR body → False."""
        self.assertFalse(cdd.has_rationale_in_pr_body(None))

    def test_empty_pr_body(self) -> None:
        """Empty PR body → False."""
        self.assertFalse(cdd.has_rationale_in_pr_body(""))

    def test_no_rationale(self) -> None:
        """No rationale in body → False."""
        body = "## Summary\nSome changes\nCloses #1"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_docs_not_needed(self) -> None:
        """"Docs not needed:..." → True."""
        body = "## Summary\n\n## Documentation\nDocs not needed: copy-only change"
        self.assertTrue(cdd.has_rationale_in_pr_body(body))

    def test_documentation_not_needed(self) -> None:
        """"Documentation not needed:..." → True."""
        body = "## Documentation\nDocumentation not needed: minor refactor"
        self.assertTrue(cdd.has_rationale_in_pr_body(body))

    def test_dash_variants(self) -> None:
        """Various dash variants → True."""
        for phrase in [
            "docs-not-needed: typo fix",
            "docs_not_needed: typo fix",
            "Docs not needed: typo fix",
        ]:
            body = f"## Documentation\n{phrase}"
            self.assertTrue(cdd.has_rationale_in_pr_body(body), f"Failed for: {phrase}")

    def test_blank_docs_not_needed(self) -> None:
        """Blank "Docs not needed:" field → False."""
        body = "## Documentation\nDocs not needed:\n\nSome content"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_blank_docs_not_needed_with_comment(self) -> None:
        """Blank "Docs not needed:" followed by HTML comment → False."""
        body = "## Documentation\nDocs not needed:\n\n<!-- Do not request Copilot Review -->"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_blank_docs_not_needed_trailing_spaces(self) -> None:
        """"Docs not needed:   " with trailing spaces → False."""
        body = "## Documentation\nDocs not needed:   "
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_blank_docs_not_needed_next_line(self) -> None:
        """Blank "Docs not needed:" with text on next line → False."""
        body = "## Documentation\nDocs not needed:\nThis is not a valid rationale"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_blank_documentation_not_needed(self) -> None:
        """Blank "Documentation not needed:" → False."""
        body = "## Documentation\nDocumentation not needed:\n"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_blank_docs_not_needed_dash(self) -> None:
        """Blank "docs-not-needed:" → False."""
        body = "## Documentation\ndocs-not-needed:\n"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))

    def test_blank_docs_not_needed_underscore(self) -> None:
        """Blank "docs_not_needed:" → False."""
        body = "## Documentation\ndocs_not_needed:\n"
        self.assertFalse(cdd.has_rationale_in_pr_body(body))


class MainIntegrationTest(unittest.TestCase):
    """Tests for main() via _run()."""

    def test_docs_only_pr(self) -> None:
        """Pure docs change → no warning."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "README.md", "docs/ROADMAP.md",
        ])
        self.assertEqual(code, 0)
        # ROADMAP.md will trigger the ROADMAP area, but also is the relevant doc, so passes
        self.assertIn("passed", output)
        self.assertNotIn("Warning", output)

    def test_behaviour_change_no_docs(self) -> None:
        """Behaviour change with no docs → warning."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "feature/chat/Screen.kt",
        ])
        self.assertEqual(code, 0)  # Always 0
        self.assertIn("Documentation Drift Warning", output)

    def test_behaviour_change_with_docs(self) -> None:
        """Behaviour change with relevant docs update → no warning."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "feature/chat/Screen.kt", "docs/UX_PATTERNS.md",
        ])
        self.assertEqual(code, 0)
        self.assertIn("passed", output)
        self.assertNotIn("Documentation Drift Warning", output)

    def test_behaviour_change_with_rationale(self) -> None:
        """Behaviour change with docs-not-needed rationale → no warning."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "feature/chat/Screen.kt",
            "--pr-body", "## Documentation\nDocs not needed: minor polish",
        ])
        self.assertEqual(code, 0)
        self.assertIn("passed", output)
        self.assertNotIn("Documentation Drift Warning", output)

    def test_behaviour_change_blank_pr_template(self) -> None:
        """Behaviour change with blank "Docs not needed:" field → warning."""
        # Simulates default PR template with empty docs-not-needed field
        template = "## Documentation\nDocs not needed:\n\n<!-- Do not request Copilot Review -->"
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "feature/chat/Screen.kt",
            "--pr-body", template,
        ])
        self.assertEqual(code, 0)
        self.assertIn("Documentation Drift Warning", output)

    def test_unknown_file_no_warning(self) -> None:
        """Unknown/non-sensitive file change → no warning."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", ".gitignore", "README.md",
        ])
        self.assertEqual(code, 0)
        self.assertNotIn("Documentation Drift Warning", output)

    def test_test_harness_change_points_to_test_docs(self) -> None:
        """Test harness change without doc update → warning referencing test docs."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "scripts/adb_skill_test.py",
        ])
        self.assertEqual(code, 0)
        self.assertIn("Documentation Drift Warning", output)
        self.assertIn("Test harness", output)
        self.assertIn("docs/automated-testing.md", output)

    def test_voice_change_points_to_voice_docs(self) -> None:
        """Voice change without doc update → warning referencing voice docs."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files", "core/voice/src/SttEngine.kt",
        ])
        self.assertEqual(code, 0)
        self.assertIn("Documentation Drift Warning", output)
        self.assertIn("Voice / STT", output)
        self.assertIn("review-gates-voice.md", output)

    def test_multiple_areas_all_listed(self) -> None:
        """Multiple areas → all listed in warning."""
        output, code = _run([
            "--base-ref", "HEAD~1",
            "--head-ref", "HEAD",
            "--changed-files",
            "feature/chat/Screen.kt",
            "core/voice/Engine.kt",
            "build.gradle.kts",
        ])
        self.assertEqual(code, 0)
        self.assertIn("User-facing / UX", output)
        self.assertIn("Voice / STT", output)
        self.assertIn("Architecture", output)

    def test_exit_code_always_zero(self) -> None:
        """Always exits 0 under all conditions."""
        for changed in [
            [".gitignore"],
            ["feature/chat/Screen.kt"],
            ["docs/SPECIFICATION.md"],
            ["feature/chat/Screen.kt", "docs/SPECIFICATION.md"],
        ]:
            _, code = _run([
                "--base-ref", "HEAD~1",
                "--head-ref", "HEAD",
                "--changed-files"] + changed)
            self.assertEqual(code, 0, f"Non-zero exit for {changed}")


if __name__ == "__main__":
    unittest.main()
