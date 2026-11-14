#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: $0 <app-release.aab>" >&2
  exit 2
fi
AAB=$(realpath "$1")
for tool in keytool sha256sum python3; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 2; }
done
if command -v bundletool >/dev/null; then
  manifest=$(bundletool dump manifest --bundle="$AAB" --module=base)
elif [[ -n "${BUNDLETOOL_JAR:-}" && -f "$BUNDLETOOL_JAR" ]] && command -v java >/dev/null; then
  manifest=$(java -jar "$BUNDLETOOL_JAR" dump manifest --bundle="$AAB" --module=base)
elif [[ -n "${BUNDLETOOL_CLASSPATH:-}" ]] && command -v java >/dev/null; then
  # Set by :app:verifyReleaseBundleForPlayUpload from Gradle's resolved bundletool dependency.
  manifest=$(java -cp "$BUNDLETOOL_CLASSPATH" com.android.tools.build.bundletool.BundleToolMain dump manifest --bundle="$AAB" --module=base)
else
  echo "Missing required tool: bundletool (or set BUNDLETOOL_JAR, or run :app:verifyReleaseBundleForPlayUpload)." >&2
  exit 2
fi
cert_output=$(keytool -printcert -jarfile "$AAB" 2>&1) || cert_output=""
cert_sha=$(printf '%s\n' "$cert_output" | awk '/SHA256:/{print $2; exit}' | tr -d ':')
file_sha=$(sha256sum "$AAB" | cut -d ' ' -f1)
MANIFEST="$manifest" AAB="$AAB" CERT_SHA="$cert_sha" FILE_SHA="$file_sha" python3 - <<'PY'
import os
import zipfile
import xml.etree.ElementTree as ET

ns = '{http://schemas.android.com/apk/res/android}'
root = ET.fromstring(os.environ['MANIFEST'])
app = root.find('application')
package = root.attrib.get('package', '')
version_code = root.attrib.get(ns + 'versionCode', '')
version_name = root.attrib.get(ns + 'versionName', '')
sdk = root.find('uses-sdk')
min_sdk = sdk.attrib.get(ns + 'minSdkVersion', '') if sdk is not None else ''
target_sdk = sdk.attrib.get(ns + 'targetSdkVersion', '') if sdk is not None else ''
debuggable = app is not None and app.attrib.get(ns + 'debuggable', 'false').lower() == 'true'
permissions = sorted({e.attrib.get(ns + 'name', '') for e in root if e.tag in ('uses-permission', 'uses-permission-sdk-23', 'uses-permission-sdk-m') and e.attrib.get(ns + 'name')})
with zipfile.ZipFile(os.environ['AAB']) as zf:
    abis = sorted({n.split('/')[2] for n in zf.namelist() if n.startswith('base/lib/') and len(n.split('/')) > 3 and n.split('/')[2]})
print(f'Package: {package}')
print(f'Version code: {version_code}')
print(f'Version name: {version_name}')
print(f'minSdk: {min_sdk}')
print(f'targetSdk: {target_sdk}')
print('Signer certificate SHA-256: ' + (os.environ['CERT_SHA'] or 'UNSIGNED'))
print(f'AAB SHA-256: {os.environ["FILE_SHA"]}')
print('Requested permissions:')
for permission in permissions:
    print(f'  {permission}')
print('Native ABIs: ' + ', '.join(abis))
print(f'Total size: {os.path.getsize(os.environ["AAB"])} bytes')
if package != 'ai.jandal.assistant':
    raise SystemExit('ERROR: package must be ai.jandal.assistant')
if debuggable:
    raise SystemExit('ERROR: debuggable=true')
if not os.environ['CERT_SHA']:
    raise SystemExit('ERROR: AAB is unsigned')
PY
