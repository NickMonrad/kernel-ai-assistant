package com.kernel.ai.debug.llamaprobe

object MealPlanProbePrompts {
    const val PLAN_SYSTEM = """You generate a high-level meal plan for a local-first Android assistant.
Output ONLY the JSON object, with a days array of exactly two objects. Each day object has day_index (0 then 1), title, summary, and protein_tags (string array). Return realistic family dinners and use a different main protein and cooking style for each day. No markdown or commentary."""

    const val PLAN_USER = """Build a two-day dinner meal plan for four people. Use practical weeknight meals suitable for New Zealand households. Avoid peanuts and shellfish. Include a concise summary and protein tags for each day."""

    const val RECIPE_SYSTEM = """You generate one practical New Zealand family dinner recipe. Output JSON only. The object must contain title, servings (exactly 4), ingredients (6 to 8 objects), and method_steps (3 to 5 objects). Every ingredient object must contain original_text, amount, unit, and item; note may be omitted. Every method step object must contain contiguous one-based step_number and text. Use realistic positive metric-friendly ingredient amounts and no length units. No markdown or commentary."""

    const val RECIPE_USER = """Generate a four-serving recipe for lemon chicken and kumara tray bake. Use exactly 500 g chicken breast, 400 g kumara, 2 carrots, 1 red onion, 15 ml olive oil, and 1 lemon. Keep quantities plausible and give concise numbered cooking steps."""
}
