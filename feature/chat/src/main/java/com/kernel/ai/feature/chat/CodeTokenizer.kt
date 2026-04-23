package com.kernel.ai.feature.chat

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Token types produced by language tokenizers.
 */
sealed interface TokenType {
    data object Keyword : TokenType
    data object Type : TokenType
    data object StringLit : TokenType
    data object NumberLit : TokenType
    data object Comment : TokenType
    data object Punctuation : TokenType
    data object Operator : TokenType
    data object Annotation : TokenType
    data object Function : TokenType
    data object Variable : TokenType
    data object Whitespace : TokenType
    data object Newline : TokenType
    data object Other : TokenType
}

/**
 * A single lexical token with its position, content, and type.
 */
data class Token(
    val type: TokenType,
    val content: String,
    val start: Int,
    val end: Int,
)

/**
 * Result of tokenizing code in a specific language.
 */
data class TokenizedCode(
    val language: String,
    val tokens: List<Token>,
)

/**
 * Tokenizes a single line of code.
 * Returns a list of tokens representing the lexical structure of the line.
 */
fun tokenizeLine(language: String, line: String): List<Token> {
    val tokenizer = getTokenizer(language)
    return try {
        tokenizer.tokenize(line)
    } catch (e: Exception) {
        // Fallback: return the entire line as "Other"
        listOf(Token(TokenType.Other, line, 0, line.length))
    }
}

/**
 * Tokenizes all lines of code and returns the full tokenized result.
 */
fun tokenizeCode(language: String, code: String): TokenizedCode {
    val allTokens = mutableListOf<Token>()
    val lines = code.lines()
    for ((i, line) in lines.withIndex()) {
        val tokens = tokenizeLine(language, line)
        allTokens.addAll(tokens)
        if (i < lines.size - 1) {
            allTokens.add(Token(TokenType.Newline, "\n", allTokens.lastOrNull()?.end ?: 0, allTokens.lastOrNull()?.end ?: 0))
        }
    }
    return TokenizedCode(language.lowercase(), allTokens)
}

/**
 * Async version that runs on Dispatchers.Default.
 */
suspend fun tokenizeCodeAsync(
    language: String,
    code: String,
    context: CoroutineContext = Dispatchers.Default,
): TokenizedCode = withContext(context) {
    tokenizeCode(language, code)
}

// ── Tokenizer registry ─────────────────────────────────────────────────────────

private val tokenizers = mutableMapOf<String, LanguageTokenizer>()

/**
 * Registers a language tokenizer. Called by language-specific modules.
 */
fun registerTokenizer(language: String, tokenizer: LanguageTokenizer) {
    tokenizers[language.lowercase()] = tokenizer
    // Also register common aliases
    when (language.lowercase()) {
        "kotlin" -> {
            tokenizers["kt"] = tokenizer
        }
        "javascript" -> {
            tokenizers["js"] = tokenizer
        }
        "typescript" -> {
            tokenizers["ts"] = tokenizer
        }
        "json" -> {
            tokenizers["jsonc"] = tokenizer
        }
    }
}

/**
 * Looks up a tokenizer for the given language, falling back to a generic tokenizer.
 */
private fun getTokenizer(language: String): LanguageTokenizer {
    val key = language.lowercase().trim()
    return tokenizers[key] ?: GenericTokenizer()
}

// ── Language tokenizer interface ───────────────────────────────────────────────

/**
 * Interface for language-specific tokenizers.
 */
interface LanguageTokenizer {
    fun tokenize(line: String): List<Token>
}

// ── Generic fallback tokenizer ─────────────────────────────────────────────────

/**
 * A basic tokenizer that recognizes strings and comments for any C-style language.
 * Used as a fallback when no language-specific tokenizer is registered.
 */
open class GenericTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        while (pos < line.length) {
            when (val c = line[pos]) {
                '"', '\'' -> {
                    val quote = c
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != quote) {
                        if (line[pos] == '\\') pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                '/' -> {
                    if (pos + 1 < line.length && line[pos + 1] == '/') {
                        // Line comment
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else if (pos + 1 < line.length && line[pos + 1] == '*') {
                        // Block comment start — treat rest as comment
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else {
                        tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                        pos++
                    }
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_')) pos++
                    val word = line.substring(start, pos)
                    tokens.add(Token(TokenType.Other, word, start, pos))
                }
                in "(){}[]<>" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=<>!&|^~:" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=<>!&|^~.") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty()) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    } else {
                        tokens.add(Token(TokenType.Other, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return tokens
    }
}

// ── Kotlin tokenizer ───────────────────────────────────────────────────────────

private val KOTLIN_KEYWORDS = setOf(
    "package", "import", "class", "interface", "fun", "val", "var", "type", "typealias",
    "if", "else", "when", "for", "while", "do", "return", "break", "continue",
    "throw", "try", "catch", "finally", "in", "is", "as", "this", "super",
    "object", "data", "sealed", "enum", "companion", "init", "constructor",
    "public", "private", "protected", "internal", "const", "suspend", "inline",
    "noinline", "crossinline", "tailrec", "operator", "infix", "reified",
    "by", "lazy", "lateinit", "where", "field", "it", "set", "get",
    "true", "false", "null", "is", "notnull", "to", "run", "apply", "with",
    "let", "also", "apply", "use", "measure", "repeat",
)

private val KOTLIN_TYPES = setOf(
    "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte",
    "Short", "Unit", "Any", "Nothing", "List", "Map", "Set", "Pair", "Triple",
    "Array", "MutableList", "MutableMap", "MutableSet", "Sequence", "IntRange",
    "CharSequence", "Object", "Class", "Type", "KClass",
    "CoroutineScope", "Job", "Deferred", "Flow", "MutableState",
    "Modifier", "Composable", "State",
)

private val KOTLIN_ANNOTATIONS = Regex("^@[a-zA-Z][a-zA-Z0-9_]*")

class KotlinTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        val trimmed = line.substringBefore("//").trimStart()

        // Check for annotation
        val annMatch = KOTLIN_ANNOTATIONS.matchEntire(line)
        if (annMatch != null && annMatch.range.start == 0) {
            val annText = annMatch.value
            tokens.add(Token(TokenType.Annotation, annText, 0, annText.length))
            pos = annText.length
            // Add whitespace after annotation
            while (pos < line.length && line[pos] == ' ') {
                tokens.add(Token(TokenType.Whitespace, " ", pos, pos + 1))
                pos++
            }
        }

        // Process the rest of the line
        while (pos < line.length) {
            when (val c = line[pos]) {
                '/' -> {
                    if (pos + 1 < line.length && line[pos + 1] == '/') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else {
                        tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                        pos++
                    }
                }
                '"', '\'' -> {
                    val quote = c
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != quote) {
                        if (line[pos] == '\\' && quote == '"') pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                '`' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != '`') {
                        if (line[pos] == '\\' && pos + 1 < line.length) pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.' || line[pos] == 'L' || line[pos] == 'f' || line[pos] == 'F')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_', '$' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '$')) pos++
                    val word = line.substring(start, pos)
                    when {
                        word in KOTLIN_KEYWORDS -> tokens.add(Token(TokenType.Keyword, word, start, pos))
                        word[0].isUpperCase() -> tokens.add(Token(TokenType.Type, word, start, pos))
                        else -> tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                in "({[<>])}" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=<>!&|^~:" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=<>!&|^~.:") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── Java tokenizer ─────────────────────────────────────────────────────────────

private val JAVA_KEYWORDS = setOf(
    "package", "import", "class", "interface", "enum", "extends", "implements",
    "public", "private", "protected", "static", "final", "abstract", "sealed",
    "void", "return", "if", "else", "switch", "case", "default", "for", "while",
    "do", "break", "continue", "try", "catch", "finally", "throw", "throws",
    "new", "this", "super", "instanceof", "synchronized", "volatile", "transient",
    "native", "strictfp", "assert", "const", "goto",
    "true", "false", "null",
)

private val JAVA_TYPES = setOf(
    "String", "Integer", "Long", "Float", "Double", "Boolean", "Character", "Byte",
    "Short", "Object", "Class", "Void", "System", "Math", "Arrays", "List",
    "Map", "Set", "ArrayList", "HashMap", "HashSet", "Vector", "Collection",
    "Optional", "Stream", "OptionalInt", "OptionalLong", "OptionalDouble",
)

class JavaTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < line.length) {
            when (val c = line[pos]) {
                '/' -> {
                    if (pos + 1 < line.length && line[pos + 1] == '/') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else if (pos + 1 < line.length && line[pos + 1] == '*') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else {
                        tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                        pos++
                    }
                }
                '"', '\'' -> {
                    val quote = c
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != quote) {
                        if (line[pos] == '\\') pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '$')) pos++
                    val word = line.substring(start, pos)
                    when {
                        word in JAVA_KEYWORDS -> {
                            if (word == "new" || word == "return" || word == "if" || word == "else" ||
                                word == "for" || word == "while" || word == "switch" || word == "case" ||
                                word == "try" || word == "catch" || word == "throw") {
                                tokens.add(Token(TokenType.Keyword, word, start, pos))
                            } else if (word in setOf("public", "private", "protected", "static", "final", "abstract")) {
                                tokens.add(Token(TokenType.Keyword, word, start, pos))
                            } else if (word[0].isUpperCase()) {
                                tokens.add(Token(TokenType.Type, word, start, pos))
                            } else {
                                tokens.add(Token(TokenType.Variable, word, start, pos))
                            }
                        }
                        word[0].isUpperCase() -> tokens.add(Token(TokenType.Type, word, start, pos))
                        else -> tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                in "({[<>])}" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=<>!&|^~:" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=<>!&|^~.") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                '@' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '.')) pos++
                    if (pos > start + 1) {
                        tokens.add(Token(TokenType.Annotation, line.substring(start, pos), start, pos))
                    } else {
                        tokens.add(Token(TokenType.Other, "@", start, pos))
                    }
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── JavaScript / TypeScript tokenizer ──────────────────────────────────────────

private val JS_KEYWORDS = setOf(
    "const", "let", "var", "function", "async", "await", "yield",
    "if", "else", "switch", "case", "default", "for", "while", "do", "break",
    "continue", "return", "throw", "try", "catch", "finally", "new", "delete",
    "typeof", "instanceof", "in", "of", "import", "export", "from", "as",
    "class", "extends", "super", "this", "static", "get", "set",
    "true", "false", "null", "undefined", "NaN", "Infinity",
    "void", "delete", "typeof",
    "interface", "type", "namespace", "module", "enum", "implements",
    "private", "protected", "public", "readonly", "abstract", "declare",
    "is", "keyof", "never", "unknown", "any", "string", "number", "boolean",
    "true", "false",
)

private val JS_TYPES = setOf(
    "String", "Number", "Boolean", "Object", "Array", "Map", "Set", "WeakMap",
    "WeakSet", "Promise", "RegExp", "Date", "Error", "TypeError", "SyntaxError",
    "JSON", "Math", "console", "window", "document", "globalThis",
    "ArrayBuffer", "TypedArray", "DataView", "Int8Array", "Int16Array", "Int32Array",
    "Uint8Array", "Uint16Array", "Uint32Array", "Uint8ClampedArray", "Float32Array",
    "Float64Array", "BigInt64Array", "BigUint64Array",
    "Proxy", "Reflect", "Iterator", "Iterable", "Generator",
)

class JSTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < line.length) {
            when (val c = line[pos]) {
                '/' -> {
                    if (pos + 1 < line.length) {
                        val next = line[pos + 1]
                        if (next == '/') {
                            tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                            pos = line.length
                        } else if (next == '*') {
                            tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                            pos = line.length
                        } else if (pos > 0) {
                            // Could be regex — check context
                            val prevToken = tokens.lastOrNull()
                            if (prevToken != null && prevToken.type in listOf(TokenType.Operator, TokenType.Keyword, TokenType.Punctuation, TokenType.Variable, TokenType.Other)) {
                                // Likely division
                                tokens.add(Token(TokenType.Operator, "/", pos, pos + 1))
                                pos++
                            } else {
                                // Likely regex
                                val start = pos
                                pos++
                                while (pos < line.length && line[pos] != '/' && line[pos] != '\\') {
                                    if (line[pos] == '[') {
                                        pos++
                                        while (pos < line.length && line[pos] != ']') {
                                            if (line[pos] == '\\') pos++
                                            pos++
                                        }
                                    }
                                    pos++
                                }
                                if (pos < line.length) pos++
                                while (pos < line.length && line[pos] in "gimsuy") pos++
                                tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                            }
                        } else {
                            tokens.add(Token(TokenType.Operator, "/", pos, pos + 1))
                            pos++
                        }
                    } else {
                        tokens.add(Token(TokenType.Other, "/", pos, pos + 1))
                        pos++
                    }
                }
                '"', '\'' -> {
                    val quote = c
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != quote) {
                        if (line[pos] == '\\') pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                '`' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != '`') {
                        if (line[pos] == '\\' && pos + 1 < line.length) pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_', '$' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '$')) pos++
                    val word = line.substring(start, pos)
                    if (word in JS_KEYWORDS) {
                        if (word in setOf(
                            "function", "async", "const", "let", "var", "class", "extends",
                            "if", "else", "for", "while", "return", "throw", "try", "catch",
                            "import", "export", "from", "new", "delete", "typeof",
                            "interface", "type", "enum", "namespace", "module",
                        )) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word in setOf("true", "false", "null", "undefined", "NaN", "Infinity", "void")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word in setOf("this", "super")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word in setOf("as", "from", "in", "of", "get", "set", "static")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word in setOf("is", "keyof", "never", "unknown", "any")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else {
                            tokens.add(Token(TokenType.Variable, word, start, pos))
                        }
                    } else if (word[0].isUpperCase() && word[0].isUpperCase()) {
                        tokens.add(Token(TokenType.Type, word, start, pos))
                    } else {
                        tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                in "({[<>])}" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=<>!&|^~:" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=<>!&|^~.") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                '@' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '.')) pos++
                    if (pos > start + 1) {
                        tokens.add(Token(TokenType.Annotation, line.substring(start, pos), start, pos))
                    } else {
                        tokens.add(Token(TokenType.Other, "@", start, pos))
                    }
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── Python tokenizer ───────────────────────────────────────────────────────────

private val PYTHON_KEYWORDS = setOf(
    "def", "class", "return", "if", "elif", "else", "while", "for", "in", "not",
    "and", "or", "is", "lambda", "yield", "import", "from", "as", "with", "try",
    "except", "finally", "raise", "pass", "break", "continue", "global", "nonlocal",
    "assert", "del", "True", "False", "None", "async", "await",
    "print", "range", "len", "type", "isinstance", "issubclass", "hasattr", "getattr", "setattr", "delattr",
    "super", "self", "cls",
)

private val PYTHON_TYPES = setOf(
    "String", "Integer", "Float", "Boolean", "List", "Dict", "Set", "Tuple",
    "Optional", "Any", "Union", "Type", "Object", "None", "NoneType",
    "Iterable", "Iterator", "Generator", "Callable", "Sequence", "Mapping",
    "AbstractClass", "ABC",
)

class PythonTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < line.length) {
            when (val c = line[pos]) {
                '#' -> {
                    tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                    pos = line.length
                }
                '"', '\'' -> {
                    val quote = c
                    // Check for triple-quoted string
                    if (pos + 2 < line.length && line[pos + 1] == quote && line[pos + 2] == quote) {
                        val start = pos
                        pos += 3
                        while (pos < line.length) {
                            if (pos + 2 < line.length && line[pos] == quote && line[pos + 1] == quote && line[pos + 2] == quote) {
                                pos += 3
                                break
                            }
                            pos++
                        }
                        tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                    } else {
                        val start = pos
                        pos++
                        while (pos < line.length && line[pos] != quote) {
                            if (line[pos] == '\\') pos++
                            pos++
                        }
                        if (pos < line.length) pos++
                        tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                    }
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_')) pos++
                    val word = line.substring(start, pos)
                    if (word in PYTHON_KEYWORDS) {
                        if (word in setOf("def", "class", "if", "elif", "else", "for", "while",
                                "return", "import", "from", "as", "with", "try", "except", "finally",
                                "raise", "pass", "break", "continue", "global", "nonlocal", "assert",
                                "del", "lambda", "yield", "async", "await", "and", "or", "not", "in", "is")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word in setOf("True", "False", "None")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word in setOf("self", "cls")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else {
                            tokens.add(Token(TokenType.Variable, word, start, pos))
                        }
                    } else if (word[0].isUpperCase()) {
                        tokens.add(Token(TokenType.Type, word, start, pos))
                    } else {
                        tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                '(' -> {
                    tokens.add(Token(TokenType.Punctuation, "(", pos, pos + 1))
                    pos++
                }
                ')' -> {
                    tokens.add(Token(TokenType.Punctuation, ")", pos, pos + 1))
                    pos++
                }
                '[' -> {
                    tokens.add(Token(TokenType.Punctuation, "[", pos, pos + 1))
                    pos++
                }
                ']' -> {
                    tokens.add(Token(TokenType.Punctuation, "]", pos, pos + 1))
                    pos++
                }
                '{' -> {
                    tokens.add(Token(TokenType.Punctuation, "{", pos, pos + 1))
                    pos++
                }
                '}' -> {
                    tokens.add(Token(TokenType.Punctuation, "}", pos, pos + 1))
                    pos++
                }
                '=' -> {
                    tokens.add(Token(TokenType.Operator, "=", pos, pos + 1))
                    pos++
                }
                in "+-*/%@^~" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%@^~") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                '>' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "=>!") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                '<' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "<=!>") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                '\t' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == '\t') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                ',' -> {
                    tokens.add(Token(TokenType.Punctuation, ",", pos, pos + 1))
                    pos++
                }
                ';' -> {
                    tokens.add(Token(TokenType.Punctuation, ";", pos, pos + 1))
                    pos++
                }
                '.' -> {
                    tokens.add(Token(TokenType.Punctuation, ".", pos, pos + 1))
                    pos++
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── Swift tokenizer ────────────────────────────────────────────────────────────

private val SWIFT_KEYWORDS = setOf(
    "let", "var", "func", "if", "else", "switch", "case", "for", "while", "guard",
    "return", "break", "continue", "throw", "try", "catch", "do", "defer",
    "import", "struct", "class", "enum", "protocol", "extension", "init", "deinit",
    "typealias", "subscript", "return", "self", "Self", "super", "nil",
    "public", "private", "internal", "fileprivate", "open",
    "static", "final", "override", "mutating", "nonmutating", "required",
    "convenience", "lazy", "weak", "unowned", "inout", "async", "await", "throws", "rethrows",
    "where", "in", "as", "is", "typealias", "some", "any",
    "true", "false",
)

private val SWIFT_TYPES = setOf(
    "String", "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16",
    "UInt32", "UInt64", "Float", "Double", "Bool", "Character", "Substring",
    "Array", "Dictionary", "Set", "Optional", "Never", "Void",
    "Any", "AnyObject", "AnyClass", "Type", "KeyPath", "Range", "ClosedRange",
    "Collection", "Sequence", "Iterator", "MutableCollection", "BidirectionalCollection",
    "RandomAccessCollection", "Comparable", "Equatable", "Hashable", "Codable",
    "Encodable", "Decodable", "Error", "ThrowableError",
    "View", "Body", "State", "Binding", "ObservedObject", "EnvironmentObject",
    "Published", "Environment", "ScenePhase",
)

class SwiftTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < line.length) {
            when (val c = line[pos]) {
                '/' -> {
                    if (pos + 1 < line.length && line[pos + 1] == '/') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else if (pos + 1 < line.length && line[pos + 1] == '*') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else {
                        tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                        pos++
                    }
                }
                '"', '\'' -> {
                    val quote = c
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != quote) {
                        if (line[pos] == '\\') pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_')) pos++
                    val word = line.substring(start, pos)
                    if (word in SWIFT_KEYWORDS) {
                        if (word in setOf("let", "var", "func", "if", "else", "switch", "case", "for",
                                "while", "guard", "return", "break", "continue", "throw", "try", "catch",
                                "do", "defer", "import", "struct", "class", "enum", "protocol",
                                "extension", "init", "deinit", "typealias", "subscript", "self", "Self",
                                "nil", "true", "false", "async", "await", "throws", "rethrows",
                                "where", "in", "as", "is", "some", "any", "override", "mutating",
                                "nonmutating", "required", "convenience", "lazy", "weak", "unowned",
                                "inout", "static", "final", "public", "private", "internal", "fileprivate")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else {
                            tokens.add(Token(TokenType.Variable, word, start, pos))
                        }
                    } else if (word[0].isUpperCase()) {
                        tokens.add(Token(TokenType.Type, word, start, pos))
                    } else {
                        tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                in "({[<>])}" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=<>!&|^~" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=<>!&|^~") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                ':' -> {
                    tokens.add(Token(TokenType.Punctuation, ":", pos, pos + 1))
                    pos++
                }
                ',' -> {
                    tokens.add(Token(TokenType.Punctuation, ",", pos, pos + 1))
                    pos++
                }
                '.' -> {
                    tokens.add(Token(TokenType.Punctuation, ".", pos, pos + 1))
                    pos++
                }
                '@' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '.')) pos++
                    if (pos > start + 1) {
                        tokens.add(Token(TokenType.Annotation, line.substring(start, pos), start, pos))
                    } else {
                        tokens.add(Token(TokenType.Other, "@", start, pos))
                    }
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── Go tokenizer ───────────────────────────────────────────────────────────────

private val GO_KEYWORDS = setOf(
    "var", "const", "type", "func", "go", "defer", "return", "if", "else", "switch",
    "case", "default", "for", "range", "break", "continue", "fallthrough", "goto",
    "nil", "true", "false", "iota",
    "package", "import", "chan", "select", "interface", "struct", "map", "slice",
    "go", "select", "make", "new", "append", "copy", "delete", "len", "cap",
    "close", "complex", "real", "imag", "panic", "recover", "print", "println",
)

private val GO_TYPES = setOf(
    "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16",
    "uint32", "uint64", "float32", "float64", "bool", "byte", "rune", "error",
    "complex64", "complex128", "unsafe", "uintptr",
    "String", "Int", "Int8", "Int16", "Int32", "Int64", "Uint", "Uint8", "Uint16",
    "Uint32", "Uint64", "Float32", "Float64", "Bool",
)

class GoTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < line.length) {
            when (val c = line[pos]) {
                '/' -> {
                    if (pos + 1 < line.length && line[pos + 1] == '/') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else if (pos + 1 < line.length && line[pos + 1] == '*') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else {
                        tokens.add(Token(TokenType.Operator, "/", pos, pos + 1))
                        pos++
                    }
                }
                '"', '\'', '`' -> {
                    val quote = c
                    val start = pos
                    pos++
                    if (quote == '`') {
                        // Raw string literal
                        while (pos < line.length && line[pos] != '`') pos++
                        if (pos < line.length) pos++
                    } else {
                        while (pos < line.length && line[pos] != quote) {
                            if (line[pos] == '\\') pos++
                            pos++
                        }
                        if (pos < line.length) pos++
                    }
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '.' || line[pos] == 'e' || line[pos] == 'E' || line[pos] == 'x' || line[pos] == 'p' || line[pos] == 'P')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_')) pos++
                    val word = line.substring(start, pos)
                    if (word in GO_KEYWORDS) {
                        if (word in setOf("package", "import", "func", "var", "const", "type",
                                "return", "if", "else", "for", "range", "switch", "case", "default",
                                "break", "continue", "fallthrough", "goto", "defer", "go", "select",
                                "chan", "interface", "struct", "make", "new", "nil", "true", "false",
                                "iota", "map", "append", "copy", "delete", "len", "cap", "close",
                                "panic", "recover")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word[0].isUpperCase()) {
                            tokens.add(Token(TokenType.Type, word, start, pos))
                        } else {
                            tokens.add(Token(TokenType.Variable, word, start, pos))
                        }
                    } else if (word[0].isUpperCase()) {
                        tokens.add(Token(TokenType.Type, word, start, pos))
                    } else {
                        tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                in "({[<>])}" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=!<>&|^~" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=!<>&|^~.") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                ':' -> {
                    tokens.add(Token(TokenType.Punctuation, ":", pos, pos + 1))
                    pos++
                }
                ',' -> {
                    tokens.add(Token(TokenType.Punctuation, ",", pos, pos + 1))
                    pos++
                }
                '.' -> {
                    tokens.add(Token(TokenType.Punctuation, ".", pos, pos + 1))
                    pos++
                }
                ';' -> {
                    tokens.add(Token(TokenType.Punctuation, ";", pos, pos + 1))
                    pos++
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── Rust tokenizer ─────────────────────────────────────────────────────────────

private val RUST_KEYWORDS = setOf(
    "fn", "let", "mut", "const", "static", "ref", "move", "if", "else", "loop", "for",
    "while", "break", "continue", "return", "match", "Some", "None", "Ok", "Err",
    "true", "false", "async", "await", "dyn", "impl", "trait", "use", "mod", "pub",
    "crate", "self", "super", "where", "type", "enum", "struct", "union", "unsafe",
    "extern", "in", "as", "self", "Self", "abstract", "become", "box", "do", "final",
    "override", "priv", "try", "vec", "Vec", "Option", "Result", "String", "str",
    "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128",
    "usize", "f32", "f64", "bool", "char",
)

private val RUST_TYPES = setOf(
    "String", "str", "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32",
    "u64", "u128", "usize", "f32", "f64", "bool", "char", "Vec", "Option", "Result",
    "HashMap", "HashSet", "BTreeMap", "BTreeSet", "LinkedList", "VecDeque",
    "Arc", "Mutex", "RwLock", "RefCell", "Cell", "Weak", "Pin", "Box", "RC",
    "Iterator", "IntoIterator", "IntoIterator", "From", "Into", "Display", "Debug",
    "Clone", "Copy", "PartialEq", "Eq", "PartialOrd", "Ord", "Hash", "Send", "Sync",
)

class RustTokenizer : LanguageTokenizer {
    override fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < line.length) {
            when (val c = line[pos]) {
                '/' -> {
                    if (pos + 1 < line.length && line[pos + 1] == '/') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else if (pos + 1 < line.length && line[pos + 1] == '*') {
                        tokens.add(Token(TokenType.Comment, line.substring(pos), pos, line.length))
                        pos = line.length
                    } else {
                        tokens.add(Token(TokenType.Operator, "/", pos, pos + 1))
                        pos++
                    }
                }
                '"' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] != '"') {
                        if (line[pos] == '\\') pos++
                        pos++
                    }
                    if (pos < line.length) pos++
                    tokens.add(Token(TokenType.StringLit, line.substring(start, pos), start, pos))
                }
                in '0'..'9' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isDigit() || line[pos] == '_' || line[pos] == '.')) pos++
                    tokens.add(Token(TokenType.NumberLit, line.substring(start, pos), start, pos))
                }
                in 'a'..'z', in 'A'..'Z', '_' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_')) pos++
                    val word = line.substring(start, pos)
                    if (word in RUST_KEYWORDS) {
                        if (word in setOf("fn", "let", "mut", "const", "static", "if", "else", "loop",
                                "for", "while", "break", "continue", "return", "match", "async", "await",
                                "impl", "trait", "use", "mod", "pub", "crate", "self", "super", "where",
                                "type", "enum", "struct", "union", "unsafe", "extern", "dyn", "ref", "move",
                                "Ok", "Err", "Some", "None", "true", "false", "in", "as", "Vec", "Option",
                                "Result", "String", "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16",
                                "u32", "u64", "u128", "usize", "f32", "f64", "bool", "char")) {
                            tokens.add(Token(TokenType.Keyword, word, start, pos))
                        } else if (word[0].isUpperCase()) {
                            tokens.add(Token(TokenType.Type, word, start, pos))
                        } else {
                            tokens.add(Token(TokenType.Variable, word, start, pos))
                        }
                    } else if (word[0].isUpperCase()) {
                        tokens.add(Token(TokenType.Type, word, start, pos))
                    } else {
                        tokens.add(Token(TokenType.Variable, word, start, pos))
                    }
                }
                in "({[<>])}" -> {
                    tokens.add(Token(TokenType.Punctuation, c.toString(), pos, pos + 1))
                    pos++
                }
                in "+-*/%=!<>&|^~" -> {
                    val start = pos
                    pos++
                    while (pos < line.length && line[pos] in "+-*/%=!<>&|^~.") pos++
                    tokens.add(Token(TokenType.Operator, line.substring(start, pos), start, pos))
                }
                ':' -> {
                    tokens.add(Token(TokenType.Punctuation, ":", pos, pos + 1))
                    pos++
                }
                ',' -> {
                    tokens.add(Token(TokenType.Punctuation, ",", pos, pos + 1))
                    pos++
                }
                '.' -> {
                    tokens.add(Token(TokenType.Punctuation, ".", pos, pos + 1))
                    pos++
                }
                ';' -> {
                    tokens.add(Token(TokenType.Punctuation, ";", pos, pos + 1))
                    pos++
                }
                '@' -> {
                    val start = pos
                    pos++
                    while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '.')) pos++
                    if (pos > start + 1) {
                        tokens.add(Token(TokenType.Annotation, line.substring(start, pos), start, pos))
                    } else {
                        tokens.add(Token(TokenType.Other, "@", start, pos))
                    }
                }
                '#' -> {
                    val start = pos
                    // Attribute or macro
                    if (pos + 1 < line.length && line[pos + 1] == '!') {
                        pos++
                        while (pos < line.length && line[pos].isLetter()) pos++
                        tokens.add(Token(TokenType.Keyword, line.substring(start, pos), start, pos))
                    } else {
                        tokens.add(Token(TokenType.Punctuation, "#", pos, pos + 1))
                        pos++
                    }
                }
                '$' -> {
                    tokens.add(Token(TokenType.Punctuation, "$", pos, pos + 1))
                    pos++
                }
                ' ' -> {
                    val start = pos
                    while (pos < line.length && line[pos] == ' ') pos++
                    if (tokens.isNotEmpty() && tokens.last().type != TokenType.Whitespace) {
                        tokens.add(Token(TokenType.Whitespace, line.substring(start, pos), start, pos))
                    }
                }
                else -> {
                    tokens.add(Token(TokenType.Other, c.toString(), pos, pos + 1))
                    pos++
                }
            }
        }
        return if (tokens.isEmpty()) listOf(Token(TokenType.Whitespace, "", 0, 0)) else tokens
    }
}

// ── Register all tokenizers ────────────────────────────────────────────────────

// Eager initialization - tokenizers are available immediately
object Tokenizers {
    init {
        registerTokenizer("kotlin", KotlinTokenizer())
        registerTokenizer("java", JavaTokenizer())
        registerTokenizer("javascript", JSTokenizer())
        registerTokenizer("typescript", JSTokenizer())
        registerTokenizer("python", PythonTokenizer())
        registerTokenizer("swift", SwiftTokenizer())
        registerTokenizer("go", GoTokenizer())
        registerTokenizer("rust", RustTokenizer())
    }
}

// Ensure initialization happens before first use
@Suppress("unused")
fun ensureTokenizersInitialized() {
    Tokenizers
}
