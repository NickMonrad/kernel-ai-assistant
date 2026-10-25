# Sherpa-ONNX Inflect source package (draft)

This package is for the draft four-ABI candidate in PR #1659, AAR SHA-256 `46a8e806833066bc657d8508e66e35dae8b1a04dff207ebd8538517d58b6567d`. It is not historical source evidence for the earlier retained AAR (`cf35bb1999586fb6c2f5746bfc556504a6ee8407e02ed3047f1274fa8d2008dc`), and the candidate is not merged or released.

The package contains the Sherpa-ONNX source snapshot at commit `b0592803468fa98777e328aa38136cf6ec015a05` (Git tree `a52fc3c4bdc6babea7c242048643f7f09aed18c1`), the pinned Inflect patch, the four-ABI build and packaging scripts/manifest, and the 12 pinned upstream CMake dependency source archives. The patch SHA-256 is `4c47b9c172d9849cf9f6f66a4cefefeaee9320197acfec23d8a19328accdcfa4`; dependency archive URLs and expected hashes are recorded in the build manifest. The included archives are the retained run-I CMake cache inputs; their SHA-256 values were checked against those pins before packaging. The run-I candidate AAR hashes to the same `46a8e806…` value as the draft PR artifact.

From the repository root, verify and extract without network access:

```sh
sha256sum -c third_party/sherpa-onnx/sherpa-source-inputs.sha256
mkdir extracted
tar -xzf third_party/sherpa-onnx/sherpa-onnx-v1.13.0-inflect-source-inputs.tar.gz -C extracted
(cd extracted/sherpa-onnx-v1.13.0-inflect-source-inputs && sha256sum -c SHA256SUMS)
```

The inner `SHA256SUMS` covers the packaged source snapshot, patch, build scripts/manifest, and dependency archives. The adjacent outer checksum file covers the bundle archive itself. The archive is assembled with sorted paths, normalized timestamps/ownership, and gzip without a timestamp, so the same staged inputs reproduce identical archive bytes. This verifies offline package integrity and unpacking, not an offline AAR rebuild: the CMake/NDK toolchain, ONNX Runtime binary build input, and stock base AAR are not bundled.

The upstream archives are preserved as-is; this package does not make a complete notice/SBOM inventory, licensing determination, legal-sufficiency claim, or recipient-facing permanent source-location decision. Those #1474/#1263 gates remain for Nick's review. No app AAR, app behavior, signing material, voice/model data, or signed release artifact is changed; this PR adds only a separate draft source-input archive.
