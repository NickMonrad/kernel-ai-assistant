package com.kernel.ai.debug.llamaprobe

object MealPlanProbePrompts {
    const val PLAN_SYSTEM = """You generate a high-level meal plan for a local-first Android assistant.
Output ONLY the JSON object, with a days array of exactly three objects. Each day object has day_index (0, 1, then 2), title, summary, and optional protein_tags (string array). Return realistic family dinners and use a different main protein and cooking style for each day. No markdown or commentary."""

    const val PLAN_USER = """Build a three-day dinner meal plan for four people. Use practical weeknight meals suitable for New Zealand households. Avoid peanuts and shellfish. Include a concise summary and optional protein tags for each day."""

    const val RECIPE_SYSTEM = """You generate one practical New Zealand family dinner recipe. Output JSON only. The object must contain title, servings (exactly 4), ingredients (6 to 8 objects), and method_steps (3 to 5 strings). Every ingredient object must contain original_text, amount, unit, and item; note may be omitted. Use realistic positive metric-friendly ingredient amounts and no length units. No markdown or commentary."""

    const val RECIPE_USER = """Generate a four-serving recipe for lemon chicken and kumara tray bake. Use exactly 500 g chicken breast, 400 g kumara, 2 whole carrots, 1 whole red onion, 15 ml olive oil, and 1 whole lemon. Keep quantities plausible and give concise cooking steps."""


}
