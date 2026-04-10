package com.kernel.ai.feature.chat

internal object LoadingMessages {
    // Shown while the model is initialising (ChatUiState.Loading screen)
    val modelLoading: List<String> = listOf(
        "Lacing up the jandals…",
        "Waking up the neurons, sweet as…",
        "Chucking the model on the barbie…",
        "She'll be right, loading up…",
        "Giving the AI a good tiki tour…",
        "Cranking up the inference engine…",
        "Sweet as bro, nearly there…",
        "Warming up the GPU, not even a drama…",
        "Loading weights, choice as…",
        "Just need a tick…",
    )

    // Shown in the typing indicator bubble while the model is generating
    val generating: List<String> = listOf(
        "Thinking…",
        "On it…",
        "Mull it over…",
        "Sweet as, gimme a sec…",
        "She's processing…",
        "Churning through it…",
        "Getting there…",
        "Noodling on that…",
        "Chewing the fat…",
        "Brain going brrr…",
    )

    fun randomModelLoading(): String = modelLoading.random()
    fun randomGenerating(): String = generating.random()
}
