# PR #816 handoff memory

## Branch and PR
- Worktree: `/home/lokhor/.omp/wt/kernel-ai-assistant-676`
- Branch: `feature/676-native-unit-conversion`
- PR: https://github.com/NickMonrad/kernel-ai-assistant/pull/816
- Issue: `#676`

## Current pushed state
- Latest pushed commit: `b90c750c` — `docs(#676): detail unit conversion coverage`
- Earlier pushed fix: `f4fe5185` — `fix(#676): round spoken exact conversions`
- Earlier pushed fix: `8a961ecb` — `fix(#676): cover spoken conversion variants`
- PR merge state last observed: `CLEAN`

## What is implemented in PR #816
### Deterministic native unit conversion
- Native `convert_units` intent backed by `UnitConversionEvaluator`
- Supported families:
  - mass: `mg`, `g`, `kg`, `oz`, `lb`
  - distance: `mm`, `cm`, `m`, `km`, `in`, `ft`, `yd`, `mi`
  - volume: `mL`, `L`, `tsp`, `tbsp`, `fl oz`, `cup`, `pt`, `qt`, `gal`
  - speed: `m/s`, `km/h`, `mph`
  - temperature: `celsius`, `fahrenheit`, `kelvin`

### Routing and normalization coverage
- Direct phrasing: `convert 5 miles to km`, `60 mph in m/s`, `100m to yards`
- Reversed phrasing: `how many cups are in 2 liters`, `how many cups in 2 L`, `how many yards in 350 m`
- Mixed target output: `convert 189 cm to feet and inches`
- Mixed source input: `convert 6 feet 2 inches to cm`
- Spoken/STT variants: `convert 100 km an hour to metres a second`
- Alias normalization includes uppercase short forms like `L` and `mL`

### Reply behavior
- Display text keeps full precision
- Spoken/TTS output is rounded when needed for readability
  - Example: `Convert 1 gallon to litres`
    - display: `1 gallon is 3.785411784 liters.`
    - spoken: `1 gallon is 3.79 liters.`
- Mixed feet/inches speech rounds the inches component to 1 decimal place

### Guardrails / non-goals
- Same-category conversions only
- Unsupported units fail clearly
- Invalid physical values fail clearly, including temperatures below absolute zero
- Negative non-temperature values are rejected
- Not in scope for this PR:
  - ingredient-aware cooking density conversions (`#827`)
  - currency conversion (`#831`)

## Docs updated
- `docs/SPECIFICATION.md`
  - Added `convert_units` to the native intent inventory
  - Added a detailed `convert_units` supported capabilities section with all families, phrasing types, alias normalization, guardrails, spoken rounding behavior, and non-goals
- `docs/ROADMAP.md`
  - `#676` marked as `In progress — PR #816`

## Verification last run
- `./gradlew --rerun-tasks :core:skills:testDebugUnitTest --tests "*NativeIntentHandlerTest" --tests "*QuickIntentRouterTest" --tests "*UnitConversionEvaluatorTest" :core:skills:compileDebugKotlin :app:compileDebugKotlin`
- Last observed result: exit `0`

## Manual/device checks still relevant
- `Convert 189 cm to feet and inches`
- `Convert 6 feet 2 inches to cm`
- `Convert 100 km an hour to metres a second`
- `How many cups in 2 L`
- `How many yards in 350 m`
- `Convert 1 gallon to litres`
  - confirm full-precision display plus rounded spoken output

## Related backlog issues
- `#827` ingredient-aware cooking conversions
- `#831` deterministic currency conversion
- `#832` Quick Actions voice fallthrough reply not spoken
