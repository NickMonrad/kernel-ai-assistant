package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/** The production NZ, competitor and LINZ locality assets, so the matcher is tested on the real gazetteer (#2079). */
class PlaceDidYouMeanNzAssetsTest {

    private val suggester = PlaceDidYouMeanSuggester {
        PlaceSlotMatcher(
            assetLines("nz_places.txt"),
            assetLines("competitor_places.txt"),
            assetLines("linz_localities.txt"),
        )
    }

    private fun navigate(text: String, place: String) = route("navigate_to", "destination", text, place)

    private fun weather(text: String, place: String) = route("get_weather", "location", text, place)

    private fun route(intent: String, slot: String, text: String, place: String) =
        placeDidYouMeanQuestion(
            suggester,
            QuickIntentRouter.RouteResult.RegexMatch(
                intent = QuickIntentRouter.MatchedIntent(
                    intentName = intent,
                    params = mapOf(slot to place),
                    source = "regex",
                ),
            ),
            text,
        )

    @Test
    fun paraparamuIsAskedAsParaparaumu() {
        val question = navigate("navigate to Paraparamu", "Paraparamu")
        assertEquals("Did you mean Paraparaumu?", question?.prompt)
        assertEquals("navigate to Paraparaumu", question?.suggestedText)
    }

    @Test
    fun porirwaIsAskedAsPorirua() {
        assertEquals("Did you mean Porirua?", navigate("navigate to Porirwa", "Porirwa")?.prompt)
    }

    @Test
    fun porirauIsAskedAsPorirua() {
        assertEquals("Did you mean Porirua?", navigate("navigate to Porirau", "Porirau")?.prompt)
    }

    @Test
    fun fungaDayIsAskedAsWhangarei() {
        assertEquals("Did you mean Whangārei?", navigate("navigate to Funga Day", "Funga Day")?.prompt)
    }

    @Test
    fun addressDetailsSurviveTheYes() {
        assertEquals("navigate to 12 Porirua Street", navigate("navigate to 12 Porirwa Street", "12 Porirwa Street")?.suggestedText)
    }

    @Test
    fun exactNzNameWithoutMacronIsNotAsked() {
        assertNull(weather("weather in Whangarei", "Whangarei"))
    }

    @Test
    fun exactNzNameIsNotAsked() {
        assertNull(navigate("navigate to Porirua", "Porirua"))
    }

    @Test
    fun knownLocalitiesAreNotAsked() {
        for (name in listOf("Takapuna", "Orewa", "Clyde", "Plimmerton")) {
            assertNull(navigate("navigate to $name", name), name)
        }
    }

    @Test
    fun australianNamesAreNotAsked() {
        for (name in listOf("Beerburrum", "Baddow", "Brisbane")) {
            assertNull(navigate("navigate to $name", name), name)
        }
    }

    @Test
    fun generalDestinationsAndPeopleAreNotAsked() {
        assertNull(navigate("navigate to park", "park"))
        assertNull(navigate("navigate to the park", "the park"))
        assertNull(navigate("navigate to toms place", "toms place"))
        assertNull(navigate("navigate to Tom's place", "Tom's place"))
        assertNull(weather("weather at the beach", "the beach"))
    }

    @Test
    fun genericVenuePhrasesAreNotAskedEvenWhenAWordNearsAPlace() {
        // "petrol" alone must not be matched to Putāruru, nor "golf" to Clive, nor "community" to Granity.
        assertNull(navigate("navigate to petrol station", "petrol station"))
        assertNull(navigate("navigate to golf course", "golf course"))
        assertNull(navigate("navigate to community hall", "community hall"))
        assertNull(navigate("navigate to the factory", "the factory"))
        assertNull(navigate("navigate to the clinic", "the clinic"))
    }

    @Test
    fun nonPlaceDestinationsAreNotAsked() {
        assertNull(navigate("navigate to pizza", "pizza"))
        assertNull(navigate("navigate to my work", "my work"))
        assertNull(
            route("play_music", "destination", "play Porirwa", "Porirwa"),
            "only navigate_to and get_weather are asked about",
        )
    }

    private fun assetLines(name: String): List<String> =
        File("src/main/assets/place_dym/$name").readLines().map { it.trim() }.filter { it.isNotEmpty() }
}
