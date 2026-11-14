import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "release" / "verify_release_bundle.sh"
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.jandal.assistant" android:versionCode="1" android:versionName="0.1.0"><uses-sdk android:minSdkVersion="35" android:targetSdkVersion="36"/><uses-permission android:name="android.permission.INTERNET"/><application android:debuggable="false"/></manifest>'''


class VerifyReleaseBundleTest(unittest.TestCase):
    def test_unsigned_release_bundle_reports_metadata_then_fails_only_unsigned(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            bundletool = bin_dir / "bundletool"
            bundletool.write_text(f"#!/bin/sh\nprintf '%s' '{MANIFEST}'\n")
            keytool = bin_dir / "keytool"
            keytool.write_text("#!/bin/sh\necho 'Not a signed jar file' >&2\nexit 1\n")
            bundletool.chmod(0o755)
            keytool.chmod(0o755)
            artifact = tmp_path / "candidate.aab"
            with zipfile.ZipFile(artifact, "w") as bundle:
                bundle.writestr("base/lib/arm64-v8a/libsample.so", b"x")
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            result = subprocess.run([str(SCRIPT), str(artifact)], text=True, capture_output=True, env=env)
            self.assertEqual(result.returncode, 1)
            self.assertIn("Package: ai.jandal.assistant", result.stdout)
            self.assertIn("Version code: 1", result.stdout)
            self.assertIn("android.permission.INTERNET", result.stdout)
            self.assertIn("Native ABIs: arm64-v8a", result.stdout)
            self.assertIn("ERROR: AAB is unsigned", result.stderr)
            self.assertNotIn("ERROR: package", result.stderr)
            self.assertNotIn("ERROR: debuggable", result.stderr)


if __name__ == "__main__":
    unittest.main()
