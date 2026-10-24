package com.kernel.ai.debug.llamaprobe

import org.json.JSONArray
import org.json.JSONObject

object MealPlanProbeSchemas {
    fun plan(): String {
        val day = objectSchema(
            properties = JSONObject()
                .put("day_index", integer(0, 6))
                .put("title", string(1))
                .put("summary", string(1))
                .put("protein_tags", array(string(), min = 0)),
            required = listOf("day_index", "title", "summary"),
        )
        return objectSchema(
            properties = JSONObject().put("days", array(day, min = 3, max = 7)),
            required = listOf("days"),
        ).toString()
    }

    fun recipe(expectedServings: Int): String {
        require(expectedServings > 0) { "expectedServings must be positive" }
        val ingredient = objectSchema(
            properties = JSONObject()
                .put("original_text", string(1))
                .put("amount", string(1))
                .put("unit", string(1))
                .put("item", string(1))
                .put("note", string()),
            required = listOf("original_text", "amount", "unit", "item"),
        )
        return objectSchema(
            properties = JSONObject()
                .put("title", string(1))
                .put("servings", integer(expectedServings, expectedServings))
                .put("ingredients", array(ingredient, min = 6, max = 8))
                .put("method_steps", array(string(1), min = 3, max = 5)),
            required = listOf("title", "servings", "ingredients", "method_steps"),
        ).toString()
    }


    private fun objectSchema(properties: JSONObject, required: List<String>): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun array(items: JSONObject, min: Int, max: Int? = null): JSONObject = JSONObject()
        .put("type", "array")
        .put("items", items)
        .put("minItems", min)
        .apply { max?.let { put("maxItems", it) } }

    private fun string(min: Int = 0): JSONObject = JSONObject()
        .put("type", "string")
        .put("minLength", min)

    private fun integer(min: Int, max: Int): JSONObject = JSONObject()
        .put("type", "integer")
        .put("minimum", min)
        .put("maximum", max)
}

object JsonSchemaSubset {
    private val supportedKeywords = setOf(
        "type",
        "properties",
        "required",
        "items",
        "minItems",
        "maxItems",
        "minimum",
        "maximum",
        "minLength",
        "maxLength",
        "additionalProperties",
    )
    private val supportedTypes = setOf("object", "array", "string", "integer")

    fun validate(schemaJson: String) {
        val root = try {
            JSONObject(schemaJson)
        } catch (e: Exception) {
            throw UnsupportedSchemaException("Invalid JSON Schema document: ${e.message}")
        }
        visit(root, "#")
    }

    private fun visit(schema: JSONObject, path: String) {
        val keys = schema.keys().asSequence().toSet()
        val unsupported = keys - supportedKeywords
        if (unsupported.isNotEmpty()) {
            throw UnsupportedSchemaException(
                "Unsupported JSON Schema keyword(s) at $path: ${unsupported.sorted().joinToString()}",
            )
        }

        val type = schema.opt("type") as? String
            ?: throw UnsupportedSchemaException("Schema type must be a string at $path")
        if (type !in supportedTypes) {
            throw UnsupportedSchemaException("Unsupported JSON Schema type '$type' at $path")
        }

        if (type == "object") {
            val properties = schema.optJSONObject("properties")
                ?: throw UnsupportedSchemaException("Object properties must be an object at $path")
            if (schema.opt("additionalProperties") != false) {
                throw UnsupportedSchemaException("Only additionalProperties:false is supported at $path")
            }
            val required = schema.optJSONArray("required")
                ?: throw UnsupportedSchemaException("Object required must be an array at $path")
            val names = properties.keys().asSequence().toSet()
            for (index in 0 until required.length()) {
                val name = required.opt(index) as? String
                    ?: throw UnsupportedSchemaException("Required entries must be strings at $path")
                if (name !in names) {
                    throw UnsupportedSchemaException("Required property '$name' is undefined at $path")
                }
            }
            val iterator = properties.keys()
            while (iterator.hasNext()) {
                val name = iterator.next()
                val child = properties.optJSONObject(name)
                    ?: throw UnsupportedSchemaException("Property schema '$name' must be an object at $path")
                visit(child, "$path/properties/$name")
            }
        } else if (schema.has("properties") || schema.has("required") || schema.has("additionalProperties")) {
            throw UnsupportedSchemaException("Object keywords require type:object at $path")
        }

        if (type == "array") {
            val items = schema.optJSONObject("items")
                ?: throw UnsupportedSchemaException("Array items must be a schema object at $path")
            visit(items, "$path/items")
        } else if (schema.has("items") || schema.has("minItems") || schema.has("maxItems")) {
            throw UnsupportedSchemaException("Array keywords require type:array at $path")
        }

        validateIntegerBound(schema, path, type, "minItems", "maxItems", "array")
        validateIntegerBound(schema, path, type, "minLength", "maxLength", "string")
        validateIntegerBound(schema, path, type, "minimum", "maximum", "integer")
    }

    private fun validateIntegerBound(
        schema: JSONObject,
        path: String,
        type: String,
        lowerKey: String,
        upperKey: String,
        requiredType: String,
    ) {
        val hasBound = schema.has(lowerKey) || schema.has(upperKey)
        if (!hasBound) return
        if (type != requiredType || listOf(lowerKey, upperKey).any { key ->
                schema.has(key) && schema.opt(key) !is Int
            }
        ) {
            throw UnsupportedSchemaException("$lowerKey/$upperKey require integer $requiredType bounds at $path")
        }
        if (schema.has(lowerKey) && schema.has(upperKey) &&
            schema.getInt(lowerKey) > schema.getInt(upperKey)
        ) {
            throw UnsupportedSchemaException("$lowerKey exceeds $upperKey at $path")
        }
    }
}

class UnsupportedSchemaException(message: String) : IllegalArgumentException(message)
