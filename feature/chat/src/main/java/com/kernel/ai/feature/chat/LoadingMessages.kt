package com.kernel.ai.feature.chat

internal object LoadingMessages {

    /** 13 themed 3-step narrative sequences. One is picked randomly per loading session. */
    val themes: List<Triple<String, String, String>> = listOf(
        Triple("Gathering the stray pixels…", "Building the logic bridge…", "Painting the interface gold."),
        Triple("Mining the raw weights…", "Faceting the neural network…", "Setting the gems in the crown."),
        Triple("Preheating the GPU…", "Seasoning the data points…", "Letting the tokens simmer."),
        Triple("Adjusting the rabbit ear antennas…", "Inserting Disc 2 of 4…", "Tapping the side of the monitor."),
        Triple("Casting the initialization spell…", "Summoning the hidden layers…", "Binding the spirit to the silicon."),
        Triple("Rounding up the ducks…", "Convincing them to stand in a row…", "Giving them tiny little hats."),
        Triple("Recalibrating the flux capacitors…", "Diverting power from life support to logic…", "Engaging the hyper-drive."),
        Triple("Re-aligning the dilithium crystals…", "Venting drive plasma from the nacelles…", "Overclocking the warp core (Don't tell the Captain)."),
        Triple("Energizing the pattern buffers…", "Compensating for annular confinement beam drift…", "Materializing the neural network."),
        Triple("Adjusting the lateral sensor array…", "Filtering out subspace interference…", "Locking onto the Gemma signal."),
        Triple("Please state the nature of the medical emergency…", "Calibrating the cortical monitors…", "Stimulating the synaptic pathways."),
        Triple("Assimilating local datasets…", "Harmonizing the hive mind…", "Resistance is futile (but loading is mandatory)."),
        Triple("Modulating the phase variance…", "Reversing the polarity of the neutron flow…", "Rerouting auxiliary power to the logic sub-routines."),
    )

    /** Shown in the typing indicator while the model is streaming a response. */
    val generating: List<String> = listOf(
        "On it…",
        "Mull it over…",
        "Sweet as, gimme a sec…",
        "She's processing…",
        "Churning through it…",
        "Noodling on that…",
        "Chewing the fat…",
        "Brain going brrr…",
    )

    /** Shown as secondary text when the engine fails to load. */
    val errorMessages: List<String> = listOf(
        "Dammit Jim, I'm an AI, not a miracle worker!",
        "I'm givin' her all she's got, Captain!",
    )

    fun randomTheme(): Triple<String, String, String> = themes.random()
    fun randomGenerating(): String = generating.random()
    fun randomError(): String = errorMessages.random()
}
