"""Authored wake-verifier test prompts (#1045 spike). Inputs to the fixture generators, not transcripts."""

# Genuine wake phrases: the phrase alone and followed by a command (the detector ring
# can contain the start of the command).
GENUINE = {
    "G-alone-1": "Hey Jandal",
    "G-alone-2": "Hey, Jandal.",
    "G-alone-3": "Hey Jandal!",
    "G-alone-4": "hey jandal",
    "G-alone-5": "Hey Jandal?",
    "G-pause": "Hey... Jandal.",
    "G-cmd-time": "Hey Jandal, what time is it?",
    "G-cmd-timer": "Hey Jandal, set a timer for ten seconds.",
    "G-cmd-weather": "Hey Jandal, what's the weather like tomorrow?",
    "G-cmd-list": "Hey Jandal, add milk to my shopping list.",
    "G-cmd-call": "Hey Jandal, call Mum.",
    "G-cmd-music": "Hey Jandal, play some music.",
}

# Near-miss phrases: rhymes, other assistants' wake words, and natural NZ speech that
# contains "jandal" without being the wake phrase.
NEAR_MISS = {
    "N-sandal": "Hey Sandal",
    "N-handle": "Hey Handle",
    "N-daniel": "Hey Daniel",
    "N-randall": "Hey Randall",
    "N-vandal": "Hey Vandal",
    "N-kendall": "Hey Kendall",
    "N-channel": "Hey Channel",
    "N-candle": "Hey Candle",
    "N-gentle": "Hey Gentle",
    "N-jungle": "Hey Jungle",
    "N-angel": "Hey Angel",
    "N-janet": "Hey Janet",
    "N-jan": "Hey Jan",
    "N-scandal": "Hey Scandal",
    "N-janelle": "Hey Janelle",
    "N-mandel": "Hey Mandel",
    "N-hannah": "Hey Hannah",
    "N-hi-daniel": "Hi Daniel",
    "N-siri": "Hey Siri",
    "N-google": "Hey Google",
    "N-okay-jandal": "Okay Jandal",
    "N-thanks-jandal": "Thanks Jandal",
    "N-jandal-alone": "Jandal.",
    "N-lost-a-jandal": "I lost a jandal at the beach.",
    "N-hey-my-jandals": "Hey, where are my jandals?",
    "N-a-sandal": "A sandal",
    "N-hey-and-all": "Hey and all",
    "N-handle-it": "Hey, handle it.",
}
