# Paraformer attribution note

This note supplements `docs/VOICE_MODEL_ATTRIBUTION.md` for #868.

## Jandal app package source

Jandal downloads the Paraformer ONNX files from:

```text
csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en
```

The app uses the encoder, decoder, and tokens files from that Hugging Face model repository.

## Upstream lineage

The Hugging Face export page lists Apache-2.0 and says the ONNX files were converted from the ModelScope/DAMO Paraformer online model source.

A wider search also points to the active `modelscope/FunASR` repository. FunASR identifies the project as MIT licensed and includes Paraformer / Paraformer streaming models in its model zoo.

## Launch attribution position

For release attribution, cite both:

- app package source: `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`, Apache-2.0;
- upstream source lineage: ModelScope/DAMO Paraformer / `modelscope/FunASR`, MIT lineage.

## Remaining caution

Before final Play Store release, confirm the exact ModelScope model page for the specific Paraformer source is still available and does not add extra model-specific terms beyond the Hugging Face export page and FunASR project licence.
