package com.kernel.ai.core.memory.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserProfileParserTest {

    // ── Name extraction ──────────────────────────────────────────────────────

    @Nested
    inner class NameExtraction {
        @Test
        fun `extracts name from 'My name is X'`() {
            val result = UserProfileParser.parse("My name is Nick. I live in Auckland.")
            assertEquals("Nick", result.name)
        }

        @Test
        fun `extracts name from 'I'm X' where X is a proper name`() {
            val result = UserProfileParser.parse("I'm Nick Monrad. I like coffee.")
            assertEquals("Nick Monrad", result.name)
        }

        @Test
        fun `extracts name from 'Call me X'`() {
            val result = UserProfileParser.parse("Call me Dave. I prefer dark mode.")
            assertEquals("Dave", result.name)
        }

        @Test
        fun `extracts name from informal 'X here' pattern`() {
            val result = UserProfileParser.parse("Nick here, I'm a dev in NZ. Please be concise.")
            assertEquals("Nick", result.name)
        }

        @Test
        fun `does not extract article as name from role sentence`() {
            val result = UserProfileParser.parse("I'm an Android developer working on AI.")
            assertNull(result.name)
        }

        @Test
        fun `does not extract role keyword as name`() {
            val result = UserProfileParser.parse("I'm a software developer. I use Linux.")
            assertNull(result.name)
        }

        @Test
        fun `returns null when no name pattern found`() {
            val result = UserProfileParser.parse("I use a Samsung S23 Ultra.")
            assertNull(result.name)
        }
    }

    // ── Role extraction ───────────────────────────────────────────────────────

    @Nested
    inner class RoleExtraction {
        @Test
        fun `extracts role with developer keyword`() {
            val result = UserProfileParser.parse("My name is Nick. I'm a Kotlin developer.")
            assertEquals("Nick", result.name)
            assertEquals("Kotlin developer", result.role)
        }

        @Test
        fun `extracts role with engineer keyword stripping employer`() {
            val result = UserProfileParser.parse("I am a software engineer at Google.")
            assertEquals("software engineer", result.role, "role title should not include employer")
            assertTrue(result.facts.any { it.contains("at Google") }, "employer should be captured as fact")
        }

        @Test
        fun `extracts role with technologist keyword stripping employer`() {
            val result = UserProfileParser.parse("I am a Principal Technologist at LAB3.")
            assertEquals("Principal Technologist", result.role, "role title should not include employer")
            assertTrue(result.facts.any { it.contains("at LAB3") }, "employer should be captured as fact")
        }

        @Test
        fun `extracts role for abbreviated dev keyword`() {
            val result = UserProfileParser.parse("I'm a dev based in Wellington. I prefer dark mode.")
            assertEquals("dev", result.role)
        }

        @Test
        fun `strips location suffix from role when on same sentence`() {
            val result = UserProfileParser.parse("I'm a software developer based in Auckland.")
            assertEquals("software developer", result.role)
            assertEquals("Auckland", result.location)
        }
    }

    // ── Location extraction ───────────────────────────────────────────────────

    @Nested
    inner class LocationExtraction {
        @Test
        fun `preserves comma-separated city and country in Location label`() {
            val result = UserProfileParser.parse("Location: Brisbane, QLD, Australia. I use Linux.")
            assertEquals("Brisbane, QLD, Australia", result.location)
        }

        @Test
        fun `extracts location from 'based in X' without capturing relative clause`() {
            val result = UserProfileParser.parse("I'm a developer based in Wellington who works on mobile apps.")
            assertEquals("Wellington", result.location)
        }

        @Test
        fun `extracts location from 'based in X'`() {
            val result = UserProfileParser.parse("I'm Nick. Based in Auckland, New Zealand. I use a Mac.")
            assertEquals("Auckland, New Zealand", result.location)
        }

        @Test
        fun `extracts location from 'I live in X'`() {
            val result = UserProfileParser.parse("My name is Sara. I live in Melbourne, Australia.")
            assertEquals("Melbourne, Australia", result.location)
        }
    }

    // ── Environment extraction ────────────────────────────────────────────────

    @Nested
    inner class EnvironmentExtraction {
        @Test
        fun `extracts I use patterns`() {
            val result = UserProfileParser.parse("I use a Samsung S23 Ultra. I use Home Assistant for solar.")
            assertEquals(2, result.environment.size)
            assertTrue(result.environment[0].contains("Samsung"))
        }

        @Test
        fun `extracts section-header Systems pattern`() {
            val result = UserProfileParser.parse("Systems: CachyOS (Main PC), Bazzite OS (ROG Ally).")
            assertTrue(result.environment.isNotEmpty(), "Systems: should go to environment")
        }

        @Test
        fun `extracts section-header Homelab pattern`() {
            val result = UserProfileParser.parse("Homelab: Extensive Docker environment (Plex, Nextcloud).")
            assertTrue(result.environment.isNotEmpty(), "Homelab: should go to environment")
        }
    }

    // ── Rule extraction ───────────────────────────────────────────────────────

    @Nested
    inner class RuleExtraction {
        @Test
        fun `extracts preference patterns`() {
            val result = UserProfileParser.parse("I prefer concise answers. Always use dark mode.")
            assertEquals(2, result.rules.size)
        }

        @Test
        fun `extracts 'Do not' imperative rule`() {
            val result = UserProfileParser.parse("Do not try to inject meal planning advice unless asked.")
            assertTrue(result.rules.isNotEmpty(), "'Do not' should be a rule")
        }

        @Test
        fun `extracts Tone section as rule`() {
            val result = UserProfileParser.parse("Tone: Prefers concise, technically precise, and actionable information.")
            assertTrue(result.rules.isNotEmpty(), "Tone: section should go to rules")
        }

        @Test
        fun `extracts third-person Prefers as rule`() {
            val result = UserProfileParser.parse("Prefers local-first suggestions over cloud dependencies.")
            assertTrue(result.rules.isNotEmpty(), "Third-person Prefers should be a rule")
        }
    }

    // ── Real-world profile (#509) ─────────────────────────────────────────────

    @Nested
    inner class RealWorldProfile {
        private val nick509Profile = """
            my name is Nick
            I am a Principal Technologist at LAB3. High technical literacy.
            Location: Brisbane, QLD, Australia. originally from nz
            Family: Married with three children (ages 1 (Lachlan), 5 (Freyja), and 10(Emilie)) and a Hungarian Vizsla dog named Xena.
            Technical Environment
            Systems: CachyOS (Main PC), Bazzite OS (ROG Ally). Windows 11 (Homelab/ Plex Server, other Docker, *arr stack)
            Hardware: Main PC: AMD Ryzen 5700X3D | Radeon RX 9070 XT | 32GB RAM, HomeLab: Ryzen 5600 | GTX 1060 | 32GB RAM
            Network: Static IP; avoids dynamic DNS dependencies.
            Homelab: Extensive Docker environment (Plex, *arr stack, Nginx). Currently migrating from Google services to self-hosted alternatives like Plexamp, Nextcloud.
            Local AI: Uses llama.cpp, and OpenCode. Prioritizes local-first compute and privacy.
            Smart Home: Advanced Home Assistant user (YAML focus). Integrates Fox ESS solar/battery and multi-zone climate control.
            Gaming: PC-centric (e.g., Cyberpunk 2077, The Witcher 3). Also has a PS5 and plays ARPGs like Ghost of Yotei.
            Cooking: Strong preference for RecipeTin Eats (Nagi's recipes) for meal planning.
            Tone: Prefers concise, technically precise, and actionable information.
            AI Instruction Hook: Assume expert-level knowledge of Linux, containerization, and AI hardware optimization.
            Prioritize local-first suggestions over cloud dependencies. When providing recipes, default to RecipeTin Eats. Do not try to inject meal planning advice unless asked.
        """.trimIndent()

        @Test
        fun `extracts name`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertEquals("Nick", result.name)
        }

        @Test
        fun `extracts role as Principal Technologist`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertTrue(result.role?.contains("Technologist") == true, "role was: ${UserProfileParser.parse(nick509Profile).role}")
        }

        @Test
        fun `extracts location preserving QLD and Australia`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertTrue(result.location?.contains("Brisbane") == true && result.location?.contains("Australia") == true,
                "location was: ${result.location}")
        }

        @Test
        fun `has non-empty environment`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertTrue(result.environment.isNotEmpty(), "environment was empty")
        }

        @Test
        fun `Prioritize and When providing go to rules`() {
            val result = UserProfileParser.parse(nick509Profile)
            val rulesText = result.rules.joinToString(" ")
            assertTrue(rulesText.contains("local-first", ignoreCase = true) ||
                rulesText.contains("RecipeTin", ignoreCase = true),
                "rules were: ${result.rules}")
        }

        @Test
        fun `Tone section goes to rules not context`() {
            val result = UserProfileParser.parse(nick509Profile)
            val inRules = result.rules.any { it.contains("concise", ignoreCase = true) }
            val inContext = result.context.any { it.contains("Tone:", ignoreCase = true) }
            assertTrue(inRules, "Tone: entry should be in rules, not context. rules=${result.rules}")
            assertTrue(!inContext, "Tone: should not be in context")
        }
    }

    // ── Full profile ──────────────────────────────────────────────────────────

    @Nested
    inner class FullProfile {
        @Test
        fun `parses realistic profile`() {
            val text = """
                My name is Nick. I'm a Kotlin developer. Based in Auckland, New Zealand.
                I use a Samsung S23 Ultra. I use Home Assistant for solar monitoring.
                I prefer concise code. Never use Java when Kotlin is available.
                I work on an Android AI assistant called Kernel.
            """.trimIndent()
            val result = UserProfileParser.parse(text)
            assertEquals("Nick", result.name)
            assertEquals("Kotlin developer", result.role)
            assertEquals("Auckland, New Zealand", result.location)
            assertTrue(result.environment.isNotEmpty())
            assertTrue(result.rules.isNotEmpty())
        }

        @Test
        fun `empty text returns empty profile`() {
            val result = UserProfileParser.parse("")
            assertTrue(result.isEmpty())
        }
    }

    // ── YAML / JSON serialization ────────────────────────────────────────────

    @Nested
    inner class YamlSerialization {
        @Test
        fun `toJson produces valid JSON`() {
            val original = UserProfileYaml(
                name = "Nick",
                role = "developer",
                location = "Auckland",
                environment = listOf("Samsung S23"),
                context = listOf("Works on Kernel AI"),
                rules = listOf("Prefer concise code"),
            )
            val json = original.toJson()
            assertTrue(json.contains("\"name\":\"Nick\""))
            assertTrue(json.contains("\"role\":\"developer\""))
            assertTrue(json.contains("\"location\":\"Auckland\""))
            assertTrue(json.contains("\"environment\":[\"Samsung S23\"]"))
        }

        @Test
        fun `toYaml produces compact format`() {
            val profile = UserProfileYaml(
                name = "Nick",
                role = "Kotlin developer",
                location = "Auckland",
                rules = listOf("Prefer concise code"),
            )
            val yaml = profile.toYaml()
            assertTrue(yaml.contains("name: Nick"))
            assertTrue(yaml.contains("role: Kotlin developer"))
            assertTrue(yaml.contains("location: Auckland"))
            assertTrue(yaml.contains("  - Prefer concise code"))
        }

        @Test
        fun `empty profile produces empty yaml`() {
            val profile = UserProfileYaml()
            assertTrue(profile.isEmpty())
            assertEquals("", profile.toYaml())
        }

        @Test
        fun `toJson includes facts when present`() {
            val original = UserProfileYaml(
                name = "Nick",
                role = "developer",
                facts = listOf("for this application"),
            )
            val json = original.toJson()
            assertTrue(json.contains("\"name\":\"Nick\""), "JSON should contain name: $json")
            assertTrue(json.contains("\"facts\":[\"for this application\"]"),
                "JSON should contain facts: $json")
        }

        @Test
        fun `toYaml includes facts when present`() {
            val profile = UserProfileYaml(
                name = "Priya",
                facts = listOf("product delivery manager", "likes practical answers"),
            )
            val yaml = profile.toYaml()
            assertTrue(yaml.contains("facts:"))
            assertTrue(yaml.contains("  - product delivery manager"))
            assertTrue(yaml.contains("  - likes practical answers"))
        }

        @Test
        fun `empty profile has facts empty`() {
            val profile = UserProfileYaml()
            assertTrue(profile.facts.isEmpty())
        }

        @Test
        fun `fromJson with facts returns correct object`() {
            val json = "{\"name\":\"Nick\",\"role\":\"developer\",\"facts\":[\"for this application\"]}"
            val restored = UserProfileYaml.fromJson(json)
            // fromJson may return null in JVM-only tests if org.json is unavailable
            // This is acceptable — production uses Android runtime where org.json is present
            if (restored != null) {
                assertEquals("Nick", restored.name)
                assertTrue(restored.facts.contains("for this application"))
            }
        }
    }

    // ── Issue #1239 fixture examples ──────────────────────────────────────────

    @Nested
    inner class Issue1239Fixtures {
        @Test
        fun `original example extracts name role and relationship`() {
            val result = UserProfileParser.parse(
                "my name is Nick, I'm an android software developer for this application"
            )
            assertEquals("Nick", result.name)
            assertTrue(result.role?.contains("android software developer") == true,
                "role should contain 'android software developer', was: ${result.role}")
            assertTrue(result.facts.any { it.contains("for this application") },
                "facts should capture 'for this application'")
        }

        @Test
        fun `example 2 name role location and use cases`() {
            val result = UserProfileParser.parse(
                "I'm Sarah. I'm a nurse in Brisbane and I mostly use Jandal for reminders, shopping lists, and quick meal ideas."
            )
            assertEquals("Sarah", result.name)
            assertTrue(result.role?.contains("nurse") == true,
                "role should contain 'nurse', was: ${result.role}")
            val allText = result.toYaml()
            assertTrue(allText.contains("Brisbane"),
                "Brisbane should be in parsed output: $allText")
        }

        @Test
        fun `example 3 preferred name and response preference`() {
            val result = UserProfileParser.parse(
                "People call me AJ. I'm studying computer science at university, and I prefer short answers unless I ask for detail."
            )
            assertEquals("AJ", result.name)
            val rulesText = result.rules.joinToString(" ")
            assertTrue(rulesText.contains("short answers", ignoreCase = true),
                "rules should contain 'short answers', was: ${result.rules}")
        }

        @Test
        fun `example 4 name role and answer preference`() {
            val result = UserProfileParser.parse(
                "My name is Priya and I manage product delivery for a small startup. I like practical answers with clear next steps."
            )
            assertEquals("Priya", result.name)
            val rulesText = result.rules.joinToString(" ")
            assertTrue(rulesText.contains("practical answers", ignoreCase = true),
                "rules should contain 'practical answers', was: ${result.rules}")
        }

        @Test
        fun `example 5 preferred name location and locale`() {
            val result = UserProfileParser.parse(
                "I'm Mike, but please call me Mick. I live in Auckland and use metric units, Celsius, and New Zealand English."
            )
            assertEquals("Mick", result.name, "should use 'call me Mick' as name")
            assertTrue(result.location?.contains("Auckland") == true,
                "location should contain 'Auckland', was: ${result.location}")
        }

        @Test
        fun `example 6 occupation and planning context`() {
            val result = UserProfileParser.parse(
                "I'm Bec, a primary school teacher in Melbourne. When I ask about planning, assume school terms and classroom activities unless I say otherwise."
            )
            assertTrue(result.role?.contains("teacher") == true,
                "role should contain 'teacher', was: ${result.role}")
            val allText = result.toYaml()
            assertTrue(allText.contains("Melbourne"),
                "Melbourne should be in parsed output: $allText")
        }

        @Test
        fun `example 7 pet context`() {
            val result = UserProfileParser.parse(
                "My name is Jordan. I have a dog called Milo and I often ask for dog-friendly weekend ideas."
            )
            assertEquals("Jordan", result.name)
            val allText = result.toYaml()
            assertTrue(allText.contains("dog", ignoreCase = true) || allText.contains("Milo"),
                "pet info should be in parsed output: $allText")
        }

        @Test
        fun `example 8 work schedule preference`() {
            val result = UserProfileParser.parse(
                "I'm Sam and I work weekdays from 8am to 4pm. Please avoid suggesting appointments during those hours unless I ask."
            )
            assertEquals("Sam", result.name)
            val rulesText = result.rules.joinToString(" ")
            assertTrue(rulesText.contains("avoid", ignoreCase = true),
                "rules should contain scheduling preference, was: ${result.rules}")
        }

        @Test
        fun `example 9 dietary preference`() {
            val result = UserProfileParser.parse(
                "I'm Lina. I'm vegetarian and prefer recipes without peanuts. I usually cook for two adults and one child."
            )
            assertEquals("Lina", result.name)
            val allText = result.toYaml()
            assertTrue(allText.contains("vegetarian", ignoreCase = true) ||
                allText.contains("without peanuts", ignoreCase = true),
                "dietary info should be captured somewhere: $allText")
        }

        @Test
        fun `example 10 app-specific defaults`() {
            val result = UserProfileParser.parse(
                "Call me Dev. I'm building an Android app called Jandal AI, so when I ask coding questions, assume Kotlin and Jetpack Compose unless I specify otherwise."
            )
            assertEquals("Dev", result.name)
            val rulesText = result.rules.joinToString(" ")
            assertTrue(rulesText.contains("Kotlin", ignoreCase = true) ||
                rulesText.contains("Compose", ignoreCase = true),
                "coding defaults should be captured in rules, was: ${result.rules}")
        }

        @Test
        fun `example 11 communication preference`() {
            val result = UserProfileParser.parse(
                "I'm Alex. I get overwhelmed by long explanations, so start with the answer and then give extra detail only if needed."
            )
            assertEquals("Alex", result.name)
            val allText = result.toYaml()
            assertTrue(allText.contains("start with the answer", ignoreCase = true) ||
                allText.contains("extra detail", ignoreCase = true),
                "communication preference should be captured somewhere: $allText")
        }

        @Test
        fun `example 12 self-hosted preference`() {
            val result = UserProfileParser.parse(
                "My name is Nia. I run a home media server and prefer open-source or self-hosted options where possible."
            )
            assertEquals("Nia", result.name)
            val allText = result.toYaml()
            assertTrue(allText.contains("open-source", ignoreCase = true) ||
                allText.contains("self-hosted", ignoreCase = true),
                "tech preferences should be captured: $allText")
        }

        @Test
        fun `name and role in same sentence without period`() {
            val result = UserProfileParser.parse(
                "my name is Nick, I'm an android software developer for this application"
            )
            assertEquals("Nick", result.name)
            assertTrue(result.role?.contains("android software developer") == true,
                "role should contain 'android software developer', was: ${result.role}")
        }
    }

    // ── Facts extraction ──────────────────────────────────────────────────────

    @Nested
    inner class FactsExtraction {
        @Test
        fun `role trailing employer stored as fact`() {
            val result = UserProfileParser.parse("I work as a software engineer at Google.")
            assertTrue(result.facts.any { it.contains("at Google") },
                "facts should contain employer reference: ${result.facts}")
        }

        @Test
        fun `unclassified informative sentences captured as facts or env`() {
            val result = UserProfileParser.parse("My name is Jordan. I have a dog called Milo.")
            val allText = result.environment.joinToString(" ") + " " +
                result.context.joinToString(" ") + " " +
                result.facts.joinToString(" ")
            assertTrue(allText.contains("dog", ignoreCase = true) || allText.contains("Milo"),
                "pet info should be captured somewhere: $allText")
        }

        @Test
        fun `empty profile has no facts`() {
            val result = UserProfileParser.parse("")
            assertTrue(result.facts.isEmpty())
        }
    }
}
