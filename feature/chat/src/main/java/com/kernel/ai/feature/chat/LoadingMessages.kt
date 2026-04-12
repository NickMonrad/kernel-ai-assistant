package com.kernel.ai.feature.chat

/**
 * Fun themed loading messages for Kernel AI.
 *
 * Model loading uses 3-step narrative sequences. Call [randomTheme] on each init to pick a theme,
 * then display step 1, 2, 3 sequentially as the engine warms up.
 *
 * Tone: casual, playful, slightly Kiwi (New Zealand).
 */
internal object LoadingMessages {

    /** Each theme is a Triple of (step1, step2, step3) strings. */
    val themes: List<Triple<String, String, String>> = listOf(
        // 🏗️ Digital Construction
        Triple(
            "Gathering the stray pixels…",
            "Building the logic bridge…",
            "Painting the interface gold.",
        ),
        // 💎 Gemologist (Gemma Theme)
        Triple(
            "Mining the raw weights…",
            "Faceting the neural network…",
            "Setting the gems in the crown.",
        ),
        // 🍳 Kernel Kitchen
        Triple(
            "Preheating the GPU…",
            "Seasoning the data points…",
            "Letting the tokens simmer.",
        ),
        // 💾 Retro Hardware
        Triple(
            "Adjusting the rabbit ear antennas…",
            "Inserting Disc 2 of 4…",
            "Tapping the side of the monitor.",
        ),
        // 🧙 Techno-Wizard
        Triple(
            "Casting the initialization spell…",
            "Summoning the hidden layers…",
            "Binding the spirit to the silicon.",
        ),
        // 🦆 Duck Pond
        Triple(
            "Rounding up the ducks…",
            "Convincing them to stand in a row…",
            "Giving them tiny little hats.",
        ),
        // 🛸 Sci-Fi Reboot
        Triple(
            "Recalibrating the flux capacitors…",
            "Diverting power from life support to logic…",
            "Engaging the hyper-drive.",
        ),
        // 🖖 Chief Engineer (Star Trek)
        Triple(
            "Re-aligning the dilithium crystals…",
            "Venting drive plasma from the nacelles…",
            "Overclocking the warp core (Don't tell the Captain).",
        ),
        // 🚀 Transporter Room
        Triple(
            "Energizing the pattern buffers…",
            "Compensating for annular confinement beam drift…",
            "Materializing the neural network.",
        ),
        // 📡 Sensor Sweep
        Triple(
            "Adjusting the lateral sensor array…",
            "Filtering out subspace interference…",
            "Locking onto the Gemma signal.",
        ),
        // ⚕️ Sickbay
        Triple(
            "Please state the nature of the medical emergency…",
            "Calibrating the cortical monitors…",
            "Stimulating the synaptic pathways.",
        ),
        // 🧊 Borg Collective
        Triple(
            "Assimilating local datasets…",
            "Harmonizing the hive mind…",
            "Resistance is futile (but loading is mandatory).",
        ),
        // 🪐 Technobabble Special
        Triple(
            "Modulating the phase variance…",
            "Reversing the polarity of the neutron flow…",
            "Rerouting auxiliary power to the logic sub-routines.",
        ),
        // 🥝 Kiwi Warmup
        Triple(
            "Sharpening my rugby knowledge…",
            "Checking the weather in Wellington…",
            "Dusting off my Māori vocab.",
        ),
        // 🧠 Neural Wrangling
        Triple(
            "Wrangling the neural weights…",
            "Asking the kiwi birds for wisdom…",
            "Loading up on pavlova energy.",
        ),
        // ☕ Flat White Fuel
        Triple(
            "Running on pure flat white fuel…",
            "Tuning the model, mate…",
            "Almost there — just like waiting for a pie to cool.",
        ),
        // 🍪 Biscuit Break
        Triple(
            "Your AI is having a quick biscuit…",
            "Brushing the crumbs off the keyboard…",
            "Back on deck, sweet as.",
        ),
    )

    /** Shown when something goes sideways — Scotty/McCoy energy. */
    val errorMessages: List<String> = listOf(
        "Dammit Jim, I'm an AI, not a miracle worker!",
        "I'm givin' her all she's got, Captain!",
    )

    /** Short messages shown in the typing indicator while the model is streaming a response. */
    val generating: List<String> = listOf(
        "On it…",
        "Mull it over…",
        "She's processing…",
        "Churning through it…",
        "Noodling on that…",
        "Brain going brrr…",
    )

    /** Picks a random 3-step theme for this loading session. */
    fun randomTheme(): Triple<String, String, String> = themes.random()

    /** Picks a random generating message for the typing indicator. */
    fun randomGenerating(): String = generating.random()

    /** Picks a random error fallback quip. */
    fun randomError(): String = errorMessages.random()
}
