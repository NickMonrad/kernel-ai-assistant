# Sherpa-ONNX Inflect corresponding-source input archive

This source-input archive is associated with the four-ABI Sherpa-ONNX Inflect candidate AAR (SHA-256 `46a8e806833066bc657d8508e66e35dae8b1a04dff207ebd8538517d58b6567d`). It is not historical source evidence for the earlier retained AAR (SHA-256 `cf35bb1999586fb6c2f5746bfc556504a6ee8407e02ed3047f1274fa8d2008dc`).

The public repository copy is `third_party/sherpa-onnx/sherpa-onnx-v1.13.0-inflect-source-inputs.tar.gz`; the adjacent `sherpa-source-inputs.sha256` records its exact archive digest. The package contains the Sherpa-ONNX source snapshot at commit `b0592803468fa98777e328aa38136cf6ec015a05` (Git tree `a52fc3c4bdc6babea7c242048643f7f09aed18c1`), the pinned Inflect patch (SHA-256 `4c47b9c172d9849cf9f6f66a4cefefeaee9320197acfec23d8a19328accdcfa4`), the four-ABI build/packaging scripts and manifest, and 12 pinned upstream CMake dependency source archives. Dependency archive URLs and expected hashes are recorded in the included build manifest. This bundle is intended to provide public access to these source inputs for the associated candidate; it does not alone establish complete corresponding-source compliance for any release.

Verify the outer archive and extract the package without network access:

```sh
sha256sum -c third_party/sherpa-onnx/sherpa-source-inputs.sha256
mkdir extracted
tar -xzf third_party/sherpa-onnx/sherpa-onnx-v1.13.0-inflect-source-inputs.tar.gz -C extracted
(cd extracted/sherpa-onnx-v1.13.0-inflect-source-inputs && sha256sum -c SHA256SUMS)
```

The inner `SHA256SUMS` covers the packaged source snapshot, patch, build scripts/manifest, and dependency archives. The archive is assembled with sorted paths, normalized timestamps/ownership, and gzip without a timestamp. These checks verify package integrity and unpacking, not an offline AAR rebuild. CMake/NDK toolchain inputs, the ONNX Runtime binary build input, and the stock base AAR are not bundled.

The archive and source metadata do not complete the separate #1474/#1263 requirements for release-specific native-payload reconciliation, voice-pack `espeak-ng-data` provenance, recipient-facing notices/SBOM, final signed-release reconciliation, or legal/release approval. No legal-sufficiency or release-approval claim is made.