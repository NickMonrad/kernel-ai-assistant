package com.kernel.ai.core.inference

/**
 * Schema specification for constrained (structured) generation.
 *
 * The spec is translated into a synthetic OpenAPI tool call at inference time.
 * The model generates JSON that is guaranteed to match the schema because
 * constrained decoding enforces the JSON structure at the token level.
 */
data class StructuredOutputSpec(

    /** Name used for the synthetic tool call (e.g. "emit_meal_plan"). */
    val toolName: String,

    /** Human-readable description injected into the tool definition. */
    val toolDescription: String,

    /** JSON Schema (draft-07) describing the expected output object. */
    val jsonSchema: String,
) {
    companion object {
        /**
         * Canonical schema for a high-level meal-plan draft (plan generation).
         *
         * Matches [MealPlanJsonParser] contract: days array with day_index, title,
         * summary, and protein_tags.
         */
        val MealPlan = StructuredOutputSpec(
            toolName = "emit_meal_plan",
            toolDescription = "Emit a complete high-level meal plan with one day object per day.",
            jsonSchema = """
{
  "type": "object",
  "required": ["days"],
  "properties": {
    "days": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["day_index", "title", "summary", "protein_tags"],
        "properties": {
          "day_index": {"type": "integer"},
          "title": {"type": "string"},
          "summary": {"type": "string"},
          "protein_tags": {
            "type": "array",
            "items": {"type": "string"}
          }
        }
      }
    }
  }
}
            """.trimIndent().filterNot { it.isWhitespace() },
        )

        /**
         * Canonical schema for a single recipe (recipe generation).
         *
         * Matches [MealPlanJsonParser] contract: title, servings, ingredients
         * (array of strings), and method_steps (array of strings).
         */
        val Recipe = StructuredOutputSpec(
            toolName = "emit_recipe",
            toolDescription = "Emit a single recipe with ingredients and method steps.",
            jsonSchema = """
{
  "type": "object",
  "required": ["title", "servings", "ingredients", "method_steps"],
  "properties": {
    "title": {"type": "string"},
    "servings": {"type": "integer"},
    "ingredients": {
      "type": "array",
      "items": {"type": "string"}
    },
    "method_steps": {
      "type": "array",
      "items": {"type": "string"}
    }
  }
}
            """.trimIndent().filterNot { it.isWhitespace() },
        )

        /**
         * Canonical schema for a single meal-plan day (replacement-day generation).
         *
         * Matches [MealPlanJsonParser.parseSinglePlanDay] contract: wrapped in a
         * {days: [...]} array so the parser can extract the single day.
         */
        val ReplacementDay = StructuredOutputSpec(
            toolName = "emit_replacement_day",
            toolDescription = "Emit a single replacement meal-plan day wrapped in a days array.",
            jsonSchema = """
{
  "type": "object",
  "required": ["days"],
  "properties": {
    "days": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["day_index", "title", "summary", "protein_tags"],
        "properties": {
          "day_index": {"type": "integer"},
          "title": {"type": "string"},
          "summary": {"type": "string"},
          "protein_tags": {
            "type": "array",
            "items": {"type": "string"}
          }
        }
      }
    }
  }
}
            """.trimIndent().filterNot { it.isWhitespace() },
        )
    }
}
