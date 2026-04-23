package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Unit tests for the code tokenizer infrastructure and language tokenizers in [CodeTokenizer].
 *
 * Covers:
 *  - Core tokenizer infrastructure (TokenType, Token, tokenizeCode, tokenizeLine)
 *  - Generic fallback tokenizer
 *  - Kotlin tokenizer (keywords, types, strings, comments, annotations)
 *  - Java tokenizer (keywords, types, strings, comments)
 *  - JavaScript / TypeScript tokenizer (keywords, types, strings, regex, comments)
 *  - Python tokenizer (keywords, types, strings, comments, triple-quoted strings)
 *  - Swift tokenizer (keywords, types, strings, comments, attributes)
 *  - Go tokenizer (keywords, types, strings, comments, raw strings)
 *  - Rust tokenizer (keywords, types, strings, comments, attributes)
 *  - Language alias registration (kt, js, ts, jsonc)
 *  - Empty and edge cases
 */
class CodeTokenizerTest {

    @Nested
    @DisplayName("Core tokenizer infrastructure")
    inner class CoreTokenizer {

        @Test
        fun `tokenizeLine returns tokens for each language`() {
            val languages = listOf("kotlin", "java", "javascript", "typescript", "python", "swift", "go", "rust")
            val line = "val x = 42"
            for (lang in languages) {
                val tokens = tokenizeLine(lang, line)
                assertTrue(tokens.isNotEmpty(), "Language '$lang' should produce tokens")
            }
        }

        @Test
        fun `tokenizeLine uses language-specific tokenizers not generic`() {
            val ktTokens = tokenizeLine("kotlin", "val x = 42")
            assertTrue(ktTokens.any { it.type == TokenType.Keyword && it.content == "val" }, "Kotlin should recognize 'val' as keyword")

            val pyTokens = tokenizeLine("python", "def foo():")
            assertTrue(pyTokens.any { it.type == TokenType.Keyword && it.content == "def" }, "Python should recognize 'def' as keyword")

            val goTokens = tokenizeLine("go", "func main() {}")
            assertTrue(goTokens.any { it.type == TokenType.Keyword && it.content == "func" }, "Go should recognize 'func' as keyword")

            val rsTokens = tokenizeLine("rust", "fn main() {}")
            assertTrue(rsTokens.any { it.type == TokenType.Keyword && it.content == "fn" }, "Rust should recognize 'fn' as keyword")
        }

        @Test
        fun `tokenizeLine recognizes language aliases`() {
            val ktTokens = tokenizeLine("kt", "val x = 42")
            assertTrue(ktTokens.any { it.type == TokenType.Keyword && it.content == "val" }, "Alias 'kt' should work like 'kotlin'")

            val jsTokens = tokenizeLine("js", "const x = 1")
            assertTrue(jsTokens.any { it.type == TokenType.Keyword && it.content == "const" }, "Alias 'js' should work like 'javascript'")

            val tsTokens = tokenizeLine("ts", "const x: number = 1")
            assertTrue(tsTokens.any { it.type == TokenType.Keyword && it.content == "const" }, "Alias 'ts' should work like 'typescript'")
        }

        @Test
        fun `tokenizeCode returns TokenizedCode with language and tokens`() {
            val code = "val x = 42"
            val result = tokenizeCode("kotlin", code)
            assertEquals("kotlin", result.language)
            assertTrue(result.tokens.isNotEmpty(), "Should produce tokens")
        }

        @Test
        fun `tokenizeCode preserves code content in tokens`() {
            val code = "val x = 42"
            val result = tokenizeCode("kotlin", code)
            val reconstructed = result.tokens.joinToString("") { it.content }
            assertEquals(code, reconstructed)
        }

        @Test
        fun `tokenizeCode handles multi-line code`() {
            val code = "val x = 1\nval y = 2"
            val result = tokenizeCode("kotlin", code)
            assertTrue(result.tokens.isNotEmpty())
            val hasNewline = result.tokens.any { it.type == TokenType.Newline }
            assertTrue(hasNewline, "Multi-line code should contain newline tokens")
        }

        @Test
        fun `tokenizeCode with blank language returns tokens`() {
            val code = "some code here"
            val result = tokenizeCode("", code)
            assertTrue(result.tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeCode with unrecognized language uses generic tokenizer`() {
            val code = "x = 42"
            val result = tokenizeCode("fortran", code)
            assertTrue(result.tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeLine with empty line returns whitespace token`() {
            val tokens = tokenizeLine("kotlin", "")
            assertTrue(tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeLine with whitespace returns whitespace token`() {
            val tokens = tokenizeLine("kotlin", "   ")
            assertTrue(tokens.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Generic fallback tokenizer")
    inner class GenericTokenizerTest {

        @Test
        fun `generic tokenizer recognizes strings`() {
            val line = "val x = \"hello\""
            val tokens = GenericTokenizer().tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `generic tokenizer recognizes numbers`() {
            val line = "val x = 42"
            val tokens = GenericTokenizer().tokenize(line)
            val numberTokens = tokens.filter { it.type == TokenType.NumberLit }
            assertTrue(numberTokens.isNotEmpty(), "Should recognize number literals")
        }

        @Test
        fun `generic tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = GenericTokenizer().tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `generic tokenizer recognizes punctuation`() {
            val line = "func(a, b)"
            val tokens = GenericTokenizer().tokenize(line)
            val punctTokens = tokens.filter { it.type == TokenType.Punctuation }
            assertTrue(punctTokens.isNotEmpty(), "Should recognize punctuation")
        }

        @Test
        fun `generic tokenizer recognizes operators`() {
            val line = "a + b * c"
            val tokens = GenericTokenizer().tokenize(line)
            val opTokens = tokens.filter { it.type == TokenType.Operator }
            assertTrue(opTokens.isNotEmpty(), "Should recognize operators")
        }
    }

    @Nested
    @DisplayName("Kotlin tokenizer")
    inner class KotlinTokenizerTest {

        private val tokenizer = KotlinTokenizer()

        @Test
        fun `kotlin tokenizer recognizes keywords`() {
            val line = "val x = 42"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "val" }, "Should recognize 'val' as keyword")
        }

        @Test
        fun `kotlin tokenizer recognizes type names`() {
            val line = "val x: String = \"hello\""
            val tokens = tokenizer.tokenize(line)
            val typeTokens = tokens.filter { it.type == TokenType.Type }
            assertTrue(typeTokens.any { it.content == "String" }, "Should recognize 'String' as type")
        }

        @Test
        fun `kotlin tokenizer recognizes string literals`() {
            val line = "val x = \"hello\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `kotlin tokenizer recognizes raw string literals`() {
            val line = "val x = \"\"\"hello\"\"\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize raw string literals")
        }

        @Test
        fun `kotlin tokenizer recognizes backtick strings`() {
            val line = "val x = `hello`"
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize backtick strings")
        }

        @Test
        fun `kotlin tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `kotlin tokenizer recognizes annotations`() {
            val line = "@Composable fun greet() {}"
            val tokens = tokenizer.tokenize(line)
            val annotationTokens = tokens.filter { it.type == TokenType.Annotation }
            assertTrue(annotationTokens.isNotEmpty(), "Should recognize annotations")
        }

        @Test
        fun `kotlin tokenizer recognizes numbers`() {
            val line = "val x = 42"
            val tokens = tokenizer.tokenize(line)
            val numberTokens = tokens.filter { it.type == TokenType.NumberLit }
            assertTrue(numberTokens.isNotEmpty(), "Should recognize number literals")
        }

        @Test
        fun `kotlin tokenizer recognizes operators`() {
            val line = "val x = a + b"
            val tokens = tokenizer.tokenize(line)
            val opTokens = tokens.filter { it.type == TokenType.Operator }
            assertTrue(opTokens.isNotEmpty(), "Should recognize operators")
        }

        @Test
        fun `kotlin tokenizer recognizes function names`() {
            val line = "fun greet(name: String): String { return name }"
            val tokens = tokenizer.tokenize(line)
            val otherTokens = tokens.filter { it.type == TokenType.Other }
            assertTrue(otherTokens.any { it.content == "greet" }, "Should recognize 'greet' as identifier")
        }

        @Test
        fun `kotlin tokenizer handles when expression`() {
            val line = "when (x) { 1 -> \"one\" }"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "when" }, "Should recognize 'when' as keyword")
        }

        @Test
        fun `kotlin tokenizer handles lambda`() {
            val line = "list.map { it + 1 }"
            val tokens = tokenizer.tokenize(line)
            assertTrue(tokens.isNotEmpty(), "Should tokenize lambda expression")
        }
    }

    @Nested
    @DisplayName("Java tokenizer")
    inner class JavaTokenizerTest {

        private val tokenizer = JavaTokenizer()

        @Test
        fun `java tokenizer recognizes keywords`() {
            val line = "public class Example {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "public" }, "Should recognize 'public' as keyword")
            assertTrue(keywordTokens.any { it.content == "class" }, "Should recognize 'class' as keyword")
        }

        @Test
        fun `java tokenizer recognizes types`() {
            val line = "String name = \"hello\";"
            val tokens = tokenizer.tokenize(line)
            val typeTokens = tokens.filter { it.type == TokenType.Type }
            assertTrue(typeTokens.any { it.content == "String" }, "Should recognize 'String' as type")
        }

        @Test
        fun `java tokenizer recognizes string literals`() {
            val line = "String name = \"hello\";"
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `java tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `java tokenizer recognizes block comments`() {
            val line = "/* block comment */"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize block comments")
        }

        @Test
        fun `java tokenizer recognizes annotations`() {
            val line = "@Override public void run() {}"
            val tokens = tokenizer.tokenize(line)
            val annotationTokens = tokens.filter { it.type == TokenType.Annotation }
            assertTrue(annotationTokens.isNotEmpty(), "Should recognize annotations")
        }

        @Test
        fun `java tokenizer recognizes new keyword`() {
            val line = "new ArrayList<>()"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "new" }, "Should recognize 'new' as keyword")
        }

        @Test
        fun `java tokenizer recognizes try-catch`() {
            val line = "try { } catch (Exception e) {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "try" }, "Should recognize 'try' as keyword")
            assertTrue(keywordTokens.any { it.content == "catch" }, "Should recognize 'catch' as keyword")
        }
    }

    @Nested
    @DisplayName("JavaScript tokenizer")
    inner class JSTokenizerTest {

        private val tokenizer = JSTokenizer()

        @Test
        fun `js tokenizer recognizes keywords`() {
            val line = "const x = 42"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "const" }, "Should recognize 'const' as keyword")
        }

        @Test
        fun `js tokenizer recognizes async and await`() {
            val line = "async function fetchData() { return await fetch(url) }"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "async" }, "Should recognize 'async' as keyword")
            assertTrue(keywordTokens.any { it.content == "await" }, "Should recognize 'await' as keyword")
        }

        @Test
        fun `js tokenizer recognizes string literals`() {
            val line = "const msg = \"hello\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `js tokenizer recognizes template literals`() {
            val line = "`hello ${"$"}{name}`"
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize template literals")
        }

        @Test
        fun `js tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `js tokenizer recognizes arrow functions`() {
            val line = "const fn = (a, b) => a + b"
            val tokens = tokenizer.tokenize(line)
            assertTrue(tokens.isNotEmpty(), "Should tokenize arrow functions")
        }

        @Test
        fun `js tokenizer recognizes import and export`() {
            val line = "import { foo } from 'bar'"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "import" }, "Should recognize 'import' as keyword")
            assertTrue(keywordTokens.any { it.content == "from" }, "Should recognize 'from' as keyword")
        }

        @Test
        fun `js tokenizer recognizes class syntax`() {
            val line = "class Foo extends Bar {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "class" }, "Should recognize 'class' as keyword")
            assertTrue(keywordTokens.any { it.content == "extends" }, "Should recognize 'extends' as keyword")
        }
    }

    @Nested
    @DisplayName("Python tokenizer")
    inner class PythonTokenizerTest {

        private val tokenizer = PythonTokenizer()

        @Test
        fun `python tokenizer recognizes keywords`() {
            val line = "def hello(name: str) -> str:"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "def" }, "Should recognize 'def' as keyword")
        }

        @Test
        fun `python tokenizer recognizes string literals`() {
            val line = "msg = \"hello\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `python tokenizer recognizes triple-quoted strings`() {
            val line = "\"\"\"\"\"\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize triple-quoted strings")
        }

        @Test
        fun `python tokenizer recognizes comments`() {
            val line = "# This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `python tokenizer recognizes if and else`() {
            val line = "if x > 0:"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "if" }, "Should recognize 'if' as keyword")
        }

        @Test
        fun `python tokenizer recognizes for loop`() {
            val line = "for item in items:"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "for" }, "Should recognize 'for' as keyword")
            assertTrue(keywordTokens.any { it.content == "in" }, "Should recognize 'in' as keyword")
        }

        @Test
        fun `python tokenizer recognizes type hints`() {
            val line = "def foo(x: int) -> List[str]:"
            val tokens = tokenizer.tokenize(line)
            val typeTokens = tokens.filter { it.type == TokenType.Type }
            assertTrue(typeTokens.any { it.content == "int" }, "Should recognize 'int' as type")
            assertTrue(typeTokens.any { it.content == "List" }, "Should recognize 'List' as type")
        }

        @Test
        fun `python tokenizer recognizes self`() {
            val line = "def foo(self):"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "self" }, "Should recognize 'self' as keyword")
        }
    }

    @Nested
    @DisplayName("Swift tokenizer")
    inner class SwiftTokenizerTest {

        private val tokenizer = SwiftTokenizer()

        @Test
        fun `swift tokenizer recognizes keywords`() {
            val line = "let x = 42"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "let" }, "Should recognize 'let' as keyword")
        }

        @Test
        fun `swift tokenizer recognizes var keyword`() {
            val line = "var name: String = \"hello\""
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "var" }, "Should recognize 'var' as keyword")
        }

        @Test
        fun `swift tokenizer recognizes struct keyword`() {
            val line = "struct MyStruct {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "struct" }, "Should recognize 'struct' as keyword")
        }

        @Test
        fun `swift tokenizer recognizes string literals`() {
            val line = "let msg = \"hello\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `swift tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `swift tokenizer recognizes attributes`() {
            val line = "@State var count = 0"
            val tokens = tokenizer.tokenize(line)
            val annotationTokens = tokens.filter { it.type == TokenType.Annotation }
            assertTrue(annotationTokens.isNotEmpty(), "Should recognize attributes")
        }

        @Test
        fun `swift tokenizer recognizes types`() {
            val line = "let x: Int = 42"
            val tokens = tokenizer.tokenize(line)
            val typeTokens = tokens.filter { it.type == TokenType.Type }
            assertTrue(typeTokens.any { it.content == "Int" }, "Should recognize 'Int' as type")
        }

        @Test
        fun `swift tokenizer recognizes guard`() {
            val line = "guard let x = optional else { return }"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "guard" }, "Should recognize 'guard' as keyword")
        }

        @Test
        fun `swift tokenizer recognizes async and throws`() {
            val line = "func fetchData() async throws -> Data"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "async" }, "Should recognize 'async' as keyword")
            assertTrue(keywordTokens.any { it.content == "throws" }, "Should recognize 'throws' as keyword")
        }
    }

    @Nested
    @DisplayName("Go tokenizer")
    inner class GoTokenizerTest {

        private val tokenizer = GoTokenizer()

        @Test
        fun `go tokenizer recognizes keywords`() {
            val line = "package main"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "package" }, "Should recognize 'package' as keyword")
        }

        @Test
        fun `go tokenizer recognizes func keyword`() {
            val line = "func main() {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "func" }, "Should recognize 'func' as keyword")
        }

        @Test
        fun `go tokenizer recognizes string literals`() {
            val line = "msg := \"hello\""
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `go tokenizer recognizes raw string literals`() {
            val line = "msg := `hello`"
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize raw string literals")
        }

        @Test
        fun `go tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `go tokenizer recognizes types`() {
            val line = "var x int"
            val tokens = tokenizer.tokenize(line)
            val typeTokens = tokens.filter { it.type == TokenType.Type }
            assertTrue(typeTokens.any { it.content == "int" }, "Should recognize 'int' as type")
        }

        @Test
        fun `go tokenizer recognizes struct keyword`() {
            val line = "type MyStruct struct {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "type" }, "Should recognize 'type' as keyword")
            assertTrue(keywordTokens.any { it.content == "struct" }, "Should recognize 'struct' as keyword")
        }

        @Test
        fun `go tokenizer recognizes interface keyword`() {
            val line = "type Reader interface {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "interface" }, "Should recognize 'interface' as keyword")
        }

        @Test
        fun `go tokenizer recognizes go keyword`() {
            val line = "go func() {}()"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "go" }, "Should recognize 'go' as keyword")
        }
    }

    @Nested
    @DisplayName("Rust tokenizer")
    inner class RustTokenizerTest {

        private val tokenizer = RustTokenizer()

        @Test
        fun `rust tokenizer recognizes keywords`() {
            val line = "fn main() {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "fn" }, "Should recognize 'fn' as keyword")
        }

        @Test
        fun `rust tokenizer recognizes let keyword`() {
            val line = "let x = 42;"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "let" }, "Should recognize 'let' as keyword")
        }

        @Test
        fun `rust tokenizer recognizes string literals`() {
            val line = "let msg = \"hello\";"
            val tokens = tokenizer.tokenize(line)
            val stringTokens = tokens.filter { it.type == TokenType.StringLit }
            assertTrue(stringTokens.isNotEmpty(), "Should recognize string literals")
        }

        @Test
        fun `rust tokenizer recognizes comments`() {
            val line = "// This is a comment"
            val tokens = tokenizer.tokenize(line)
            val commentTokens = tokens.filter { it.type == TokenType.Comment }
            assertTrue(commentTokens.isNotEmpty(), "Should recognize comments")
        }

        @Test
        fun `rust tokenizer recognizes attributes`() {
            val line = "#[derive(Debug)]"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content.contains("#") }, "Should recognize attributes")
        }

        @Test
        fun `rust tokenizer recognizes types`() {
            val line = "let x: i32 = 42;"
            val tokens = tokenizer.tokenize(line)
            val typeTokens = tokens.filter { it.type == TokenType.Type }
            assertTrue(typeTokens.any { it.content == "i32" }, "Should recognize 'i32' as type")
        }

        @Test
        fun `rust tokenizer recognizes impl block`() {
            val line = "impl MyStruct {}"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "impl" }, "Should recognize 'impl' as keyword")
        }

        @Test
        fun `rust tokenizer recognizes match expression`() {
            val line = "match x { Some(v) => v, None => 0 }"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "match" }, "Should recognize 'match' as keyword")
        }

        @Test
        fun `rust tokenizer recognizes unsafe keyword`() {
            val line = "unsafe { }"
            val tokens = tokenizer.tokenize(line)
            val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
            assertTrue(keywordTokens.any { it.content == "unsafe" }, "Should recognize 'unsafe' as keyword")
        }
    }

    @Nested
    @DisplayName("Language alias registration")
    inner class LanguageAliasTest {

        @Test
        fun `tokenizeLine works with kt alias for kotlin`() {
            val tokens = tokenizeLine("kt", "val x = 42")
            assertTrue(tokens.isNotEmpty(), "Should work with 'kt' alias")
        }

        @Test
        fun `tokenizeLine works with js alias for javascript`() {
            val tokens = tokenizeLine("js", "const x = 42")
            assertTrue(tokens.isNotEmpty(), "Should work with 'js' alias")
        }

        @Test
        fun `tokenizeLine works with ts alias for typescript`() {
            val tokens = tokenizeLine("ts", "const x: number = 42")
            assertTrue(tokens.isNotEmpty(), "Should work with 'ts' alias")
        }

        @Test
        fun `tokenizeLine works with jsonc alias for json`() {
            val tokens = tokenizeLine("jsonc", "{\"key\": \"value\"}")
            assertTrue(tokens.isNotEmpty(), "Should work with 'jsonc' alias")
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {

        @Test
        fun `tokenizeCode with empty code returns tokens`() {
            val result = tokenizeCode("kotlin", "")
            assertTrue(result.tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeCode with only whitespace returns tokens`() {
            val result = tokenizeCode("kotlin", "   ")
            assertTrue(result.tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeCode with very long line returns tokens`() {
            val longLine = "val x = \"${"a".repeat(1000)}\""
            val result = tokenizeCode("kotlin", longLine)
            assertTrue(result.tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeCode with special characters returns tokens`() {
            val code = "val x = '@#$%^&*()'"
            val result = tokenizeCode("kotlin", code)
            assertTrue(result.tokens.isNotEmpty())
        }

        @Test
        fun `tokenizeLine does not throw on any input`() {
            val inputs = listOf(
                "",
                " ",
                "   ",
                "\t",
                "\n",
                "```",
                "'''",
                "\"\"\"",
                "''''",
                "@@@",
                "<<<>>>!!!",
            )
            for (input in inputs) {
                assertDoesNotThrow {
                    tokenizeLine("kotlin", input)
                }
            }
        }
    }
}
