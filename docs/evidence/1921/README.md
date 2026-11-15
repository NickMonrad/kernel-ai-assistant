# #1921 device evidence (PR #1924)

Device: S21 (SM serial redacted to model), debug build `ai.jandal.assistant.debug` 0.1.0, installed via `adb install -r` on 2026-11-15 16:19.
Method: Wellington answer seeded online, `cachedAt` in `weather_cache.preferences_pb` rewound by 3 h (10800000 ms, same digit count), Wi-Fi and mobile data disabled (`svc wifi/data disable`), query repeated. Cache restored byte-for-byte afterwards (cmp verified).

## Timings (device clock, from logcat tap timestamps)

| Case | Send tap | Geocode fail | Outcome log | Tap → outcome |
|---|---|---|---|---|
| Wellington, cache 3 h old | 16:23:35.498 | 16:23:36.152 | `Serving stale weather cache after GEOCODE_UNAVAILABLE` 16:23:36.165 | ~0.7 s |
| Dunedin, no cache | 16:24:15.812 | 16:24:16.420 | `Weather cache miss after GEOCODE_UNAVAILABLE` 16:24:16.425 | ~0.6 s |

Geocode retries once after 500 ms, then gives up. Both cases fail well inside the ~10 s target.

## Results

1. **Stale fallback (01-wellington-stale-fallback.png):** card shows `Forecast from 12:59 PM. The weather service isn't answering, so it may be out of date.` with the cached 16 °C data. Labelled with its time.
2. **No cache (02-dunedin-no-cache.png):** card shows `I couldn't reach the location service just now. Please try again in a moment.`
   **Deviation:** the issue/PR text expects `The weather service isn't answering right now…`. This path is the geocode-failure branch, which returns a location-service message. Not changed here (the user scoped this run to device evidence).
3. **Spoken text:** not captured. No TTS log lines (`KernelAI_TTS`) were emitted for these Actions runs; `log.tag.KernelAI_TTS=DEBUG` was set beforehand. Speech output was not verified.

Logs: `logcat-kernelai-weather.txt` (filtered KernelAI lines only).
