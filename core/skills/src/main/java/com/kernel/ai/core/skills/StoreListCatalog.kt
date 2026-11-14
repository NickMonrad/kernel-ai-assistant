package com.kernel.ai.core.skills

import java.util.Locale

/** Store/category aliases and list resolution order for store-list questions. */
internal object StoreListCatalog {
    private val whitespace = Regex("\\s+")
    enum class Category(
        val listCandidates: List<String>,
        val fallbackStoreName: String,
        val categoryListName: String,
        val exampleItem: String,
    ) {
        GROCERY(
            listCandidates = listOf("shopping list", "grocery list"),
            fallbackStoreName = "shopping",
            categoryListName = "grocery",
            exampleItem = "groceries",
        ),
        HARDWARE(
            listCandidates = listOf("hardware", "Bunnings", "DIY", "hardware store"),
            fallbackStoreName = "Bunnings",
            categoryListName = "hardware",
            exampleItem = "screws",
        ),
        PHARMACY(
            listCandidates = listOf("chemist", "pharmacy", "Chemist Warehouse"),
            fallbackStoreName = "chemist",
            categoryListName = "pharmacy",
            exampleItem = "prescriptions",
        ),
    }

    data class Mapping(
        val category: Category,
        /** Null for generic place words such as "the shops" or "the chemist". */
        val storeName: String?,
        /** Exact store-list title aliases are checked before category lists. */
        val storeListNames: List<String>,
    )

    private val mappings = buildMap<String, Mapping> {
        fun register(category: Category, storeName: String?, vararg aliases: String) {
            val names = if (storeName == null) emptyList() else listOf(storeName) + aliases
            val mapping = Mapping(category, storeName, names.distinct())
            (if (storeName == null) aliases.toList() else names).forEach { alias ->
                put(normalize(alias), mapping)
            }
        }

        listOf("shop", "shops", "supermarket", "grocery store", "store", "grocer").forEach {
            register(Category.GROCERY, null, it)
        }
        listOf("chemist", "the chemist", "pharmacy", "the pharmacy").forEach {
            register(Category.PHARMACY, null, it)
        }
        register(Category.HARDWARE, null, "hardware store", "the hardware store")

        register(Category.GROCERY, "Woolworths", "Woolies", "Wollies", "Countdown")
        register(
            Category.GROCERY,
            "Pak'nSave",
            "Pak n Save",
            "PakNSave",
            "Pack and Save",
            "Pack n Save",
            "Pak and Save",
            "Pak 'n Save", "Pak n' Save", "Pak'n Save",
        )
        register(Category.GROCERY, "New World")
        register(Category.GROCERY, "Four Square")
        register(Category.GROCERY, "FreshChoice")
        register(Category.GROCERY, "SuperValue")
        register(Category.GROCERY, "Coles")
        register(Category.GROCERY, "Aldi")
        register(Category.GROCERY, "IGA")
        register(Category.GROCERY, "Harris Farm")
        register(Category.GROCERY, "Foodland")
        register(Category.GROCERY, "Drakes")
        register(Category.GROCERY, "Costco")

        register(Category.HARDWARE, "Bunnings")
        register(Category.HARDWARE, "Mitre 10")
        register(Category.HARDWARE, "PlaceMakers", "Placemakers")
        register(Category.HARDWARE, "Hammer Hardware")
        register(Category.HARDWARE, "Total Tools")

        register(Category.PHARMACY, "Chemist Warehouse")
        register(Category.PHARMACY, "Unichem")
        register(Category.PHARMACY, "Life Pharmacy")
        register(Category.PHARMACY, "Priceline")
        register(Category.PHARMACY, "Amcal")
        register(Category.PHARMACY, "TerryWhite Chemmart", "Terry White Chemmart")
    }

    fun find(place: String): Mapping? = mappings[normalize(place)]

    fun missingListReply(mapping: Mapping): String {
        val storeName = mapping.storeName ?: mapping.category.fallbackStoreName
        val listType = mapping.category.categoryListName
        val example = mapping.category.exampleItem
        return "You don't have a $storeName or $listType list yet. " +
            "Say \"add $example to my $storeName list\" to start one."
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace(whitespace, " ")
}
