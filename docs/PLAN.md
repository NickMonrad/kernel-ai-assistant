# Plan: Multi-Day Weather Forecast Card (Issue #697)

## Problem
When a user asks for a multi-day forecast (e.g. "what's the weather for the next seven days"), the weather tool returns structured data with a `forecast` array, but the UI only renders the single-day card (today's weather). Multi-day forecasts appear as plain text from the tool's formatted string.

## Current Architecture

### Data Flow
```
Open-Meteo API → GetWeatherSkill.parseForecastResponse()
  ├─ text: formatted multi-day string (shown as plain text)
  └─ ToolPresentation.Weather: single-day data only (location, emoji, temp, high/low, etc.)
       ↓
  SkillResult.DirectReply(text, presentation)
       ↓
  ChatViewModel → ChatMessage(toolCall.presentation)
       ↓
  ChatScreen → ToolPresentationContent(presentation)
       ↓
  WeatherPresentationCard — renders only the single-day fields
```

### Key Files
|File|Role|
|---|---|
|`core/skills/.../natives/GetWeatherSkill.kt`|Weather tool — `parseForecastResponse()` builds both a `text` string and a `ToolPresentation.Weather` with only the first day's data|
|`core/skills/.../ToolPresentation.kt`|Sealed interface with `Weather`, `Status`, `ListPreview`, `ComputedResult` data classes + JSON serialization|
|`feature/chat/.../ToolPresentationContent.kt`|Composable dispatcher — `WeatherPresentationCard()` renders single-day only|
|`feature/chat/.../ChatScreen.kt`|Renders `ToolPresentationContent` for each assistant message with a presentation|

### What Already Exists
- The tool **already fetches** multi-day data from Open-Meteo (`forecast_days` parameter)
- The tool **already parses** all 7 days (date, high, low, precipitation, weather code, UV, sunrise/sunset)
- The tool **already formats** the multi-day data into a readable text string
- The single-day `WeatherPresentationCard` composable is fully styled and tested

### What's Missing
- `ToolPresentation.Weather` has no `forecast` field — only single-day data
- `WeatherPresentationCard` renders only the first day
- No multi-day forecast card composable exists

## Implementation

### Phase 1: Extend `ToolPresentation.Weather` with forecast data

**File:** `core/skills/src/main/java/com/kernel/ai/core/skills/ToolPresentation.kt`

1. Add a `ForecastDay` data class:
   ```kotlin
   data class ForecastDay(
       val date: String,           // e.g. "Mon 31 Mar"
       val emoji: String,          // WMO weather emoji
       val description: String,    // e.g. "Partly cloudy"
       val highText: String?,      // e.g. "High 22°C"
       val lowText: String?,       // e.g. "Low 14°C"
       val precipText: String?,    // e.g. "2.5mm rain"
       val uvText: String?,        // e.g. "UV max 6 (High)"
       val sunText: String?,       // e.g. "Sunrise 06:45 • Sunset 18:30"
   )
   ```

2. Add `forecast: List<ForecastDay> = emptyList()` to `ToolPresentation.Weather`

3. Update `toJsonObject()` — serialize forecast as JSON array
4. Update `fromJsonObject()` — deserialize forecast from JSON array

### Phase 2: Populate forecast in `GetWeatherSkill`

**File:** `core/skills/src/main/java/com/kernel/ai/core/skills/natives/GetWeatherSkill.kt`

In `parseForecastResponse()` (line 276), after building the `text` string, construct `ForecastDay` objects from the parsed JSON arrays and pass them to `ToolPresentation.Weather`:

```kotlin
val forecastDays = (0 until len).map { i ->
    ToolPresentation.Weather.ForecastDay(
        date = formattedDate,
        emoji = emoji,
        description = desc,
        highText = highLowText,  // reuse the "High X°C" text
        lowText = ...,
        precipText = rainStr,
        uvText = uvStr,
        sunText = sunStr,
    )
}
```

The `parseWeatherResponse()` (current-day-only path) and `parseForecastDayResponse()` (single target-day path) remain unchanged — they don't need forecast data.

### Phase 3: Render multi-day forecast card in UI

**File:** `feature/chat/src/main/java/com/kernel/ai/feature/chat/ToolPresentationContent.kt`

In `WeatherPresentationCard()`:

1. If `presentation.forecast.isNotEmpty()`, render a **horizontal scrollable row** of forecast day cards below the single-day card
2. Create a `ForecastDayCard` composable for each day showing:
   - Day name (bold, small)
   - Weather emoji (medium)
   - High/Low temps (small)
   - Condition description (tiny)
3. Use `HorizontalPager` or `LazyRow` with `snap` scrolling for 7 days
4. Style consistently with the existing single-day card (same `secondaryContainer` color, same typography scale)

**New composable structure:**
```
Card (secondaryContainer)
  └─ Padding(16.dp)
      └─ Column
          ├─ Location (titleMedium)
          ├─ Row: [emoji] + [temp + feelsLike]
          ├─ Description (bodyMedium)
          ├─ High/Low, Humidity, Wind, Precip, UV, AQI, Sun (bodySmall)
          └─ [IF forecast.isNotEmpty()]
              └─ HorizontalPager / LazyRow (snap)
                  └─ ForecastDayCard (per day)
```

### Phase 4: Tests

**File:** `core/skills/src/test/java/com/kernel/ai/core/skills/ToolPresentationTest.kt` (new or existing)

1. Test `ForecastDay` serialization/deserialization round-trip
2. Test `ToolPresentation.Weather` with forecast list → JSON → back
3. Test empty forecast (single-day path) still works
4. Test 7-day forecast round-trip preserves all fields

## Files Changed (4)
|File|Change|
|---|---|
|`core/skills/.../ToolPresentation.kt`|Add `ForecastDay` data class, add `forecast` field to `Weather`, update JSON serialization|
|`core/skills/.../GetWeatherSkill.kt`|Populate `forecast` list in `parseForecastResponse()`|
|`feature/chat/.../ToolPresentationContent.kt`|Add `ForecastDayCard` composable, render horizontal pager when forecast is non-empty|
|`core/skills/src/test/.../ToolPresentationTest.kt`|Add serialization tests for forecast data|

## Risks & Trade-offs

|Risk|Mitigation|
|---|---|
|Card height on 7-day forecast|Horizontal scroll keeps card height bounded; each day card is ~80dp wide, 7 days = ~560dp total|
|Backward compatibility (old app versions)|`forecast` defaults to `emptyList()` — old clients render single-day card only, no crash|
|JSON size growth|7 days × 8 fields ≈ ~400 bytes extra — negligible for chat messages|
|Reuse vs. refactor|Extending `Weather` with forecast is simpler than creating a new `Forecast` presentation type; the single-day card remains the "header" and forecast is appended below|

## Acceptance Criteria
- User asks "what's the weather for the next 7 days" → sees formatted card with today's weather + 7-day forecast row
- Each forecast day shows: day name, emoji, high/low temp, condition
- Horizontal scroll with snap behavior for 7 days
- Single-day queries (no forecast) render exactly as before
- JSON serialization round-trip preserves all fields
- All existing tests pass
