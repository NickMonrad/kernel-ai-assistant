package com.kernel.ai.core.voice

/**
 * Metadata for a single VCTK multi-speaker voice.
 *
 * [sid] maps directly to the `sid` parameter in Sherpa-ONNX's `generate(text, sid, speed)`.
 * [speakerCode] is the VCTK speaker identifier (e.g. "p225").
 * [gender] is "Female" or "Male".
 * [accent] is the speaker's primary accent label (e.g. "English", "Scottish", "Irish").
 */
data class VctkSpeaker(
    val sid: Int,
    val speakerCode: String,
    val gender: String,
    val accent: String,
) {
    val displayLabel: String get() = "$speakerCode — $gender, $accent"
}

/**
 * Bundled metadata for all 109 speakers in the `en_GB-vctk-medium` Piper model.
 *
 * Speakers are listed in sid order (0–108), matching the order Piper assigns to VCTK
 * speaker directories (p225, p226, … p376, skipping absent speaker codes).
 *
 * Accent sources: VCTK Corpus speaker-info.txt (University of Edinburgh).
 */
object VctkSpeakerMetadata {

    val speakers: List<VctkSpeaker> = listOf(
        VctkSpeaker(0,   "p225", "Female", "English"),
        VctkSpeaker(1,   "p226", "Male",   "English"),
        VctkSpeaker(2,   "p227", "Male",   "English"),
        VctkSpeaker(3,   "p228", "Female", "Scottish"),
        VctkSpeaker(4,   "p229", "Female", "English"),
        VctkSpeaker(5,   "p230", "Female", "English"),
        VctkSpeaker(6,   "p231", "Female", "English"),
        VctkSpeaker(7,   "p232", "Male",   "Irish"),
        VctkSpeaker(8,   "p233", "Female", "English"),
        VctkSpeaker(9,   "p234", "Female", "Scottish"),
        VctkSpeaker(10,  "p235", "Male",   "English"),
        VctkSpeaker(11,  "p236", "Female", "English"),
        VctkSpeaker(12,  "p237", "Male",   "English"),
        VctkSpeaker(13,  "p238", "Female", "Scottish"),
        VctkSpeaker(14,  "p239", "Male",   "English"),
        VctkSpeaker(15,  "p240", "Male",   "English"),
        VctkSpeaker(16,  "p241", "Male",   "Scottish"),
        VctkSpeaker(17,  "p243", "Male",   "English"),
        VctkSpeaker(18,  "p244", "Female", "English"),
        VctkSpeaker(19,  "p245", "Male",   "English"),
        VctkSpeaker(20,  "p246", "Male",   "Scottish"),
        VctkSpeaker(21,  "p247", "Male",   "English"),
        VctkSpeaker(22,  "p248", "Female", "English"),
        VctkSpeaker(23,  "p249", "Female", "English"),
        VctkSpeaker(24,  "p250", "Female", "Welsh"),
        VctkSpeaker(25,  "p251", "Female", "English"),
        VctkSpeaker(26,  "p252", "Male",   "English"),
        VctkSpeaker(27,  "p253", "Female", "English"),
        VctkSpeaker(28,  "p254", "Male",   "English"),
        VctkSpeaker(29,  "p255", "Male",   "English"),
        VctkSpeaker(30,  "p256", "Male",   "English"),
        VctkSpeaker(31,  "p257", "Female", "English"),
        VctkSpeaker(32,  "p258", "Male",   "English"),
        VctkSpeaker(33,  "p259", "Male",   "English"),
        VctkSpeaker(34,  "p260", "Male",   "English"),
        VctkSpeaker(35,  "p261", "Female", "Scottish"),
        VctkSpeaker(36,  "p262", "Male",   "English"),
        VctkSpeaker(37,  "p263", "Male",   "English"),
        VctkSpeaker(38,  "p264", "Female", "Northern Irish"),
        VctkSpeaker(39,  "p265", "Female", "English"),
        VctkSpeaker(40,  "p266", "Female", "English"),
        VctkSpeaker(41,  "p267", "Female", "English"),
        VctkSpeaker(42,  "p268", "Female", "Scottish"),
        VctkSpeaker(43,  "p269", "Female", "American"),
        VctkSpeaker(44,  "p270", "Male",   "English"),
        VctkSpeaker(45,  "p271", "Male",   "American"),
        VctkSpeaker(46,  "p272", "Male",   "English"),
        VctkSpeaker(47,  "p273", "Female", "English"),
        VctkSpeaker(48,  "p274", "Female", "English"),
        VctkSpeaker(49,  "p275", "Male",   "English"),
        VctkSpeaker(50,  "p276", "Female", "English"),
        VctkSpeaker(51,  "p277", "Female", "English"),
        VctkSpeaker(52,  "p278", "Male",   "Scottish"),
        VctkSpeaker(53,  "p279", "Male",   "English"),
        VctkSpeaker(54,  "p280", "Female", "English"),
        VctkSpeaker(55,  "p281", "Male",   "Indian"),
        VctkSpeaker(56,  "p282", "Female", "English"),
        VctkSpeaker(57,  "p283", "Female", "English"),
        VctkSpeaker(58,  "p284", "Male",   "English"),
        VctkSpeaker(59,  "p285", "Male",   "English"),
        VctkSpeaker(60,  "p286", "Male",   "English"),
        VctkSpeaker(61,  "p287", "Male",   "English"),
        VctkSpeaker(62,  "p288", "Female", "English"),
        VctkSpeaker(63,  "p292", "Male",   "English"),
        VctkSpeaker(64,  "p293", "Female", "English"),
        VctkSpeaker(65,  "p294", "Female", "English"),
        VctkSpeaker(66,  "p295", "Female", "English"),
        VctkSpeaker(67,  "p297", "Male",   "English"),
        VctkSpeaker(68,  "p298", "Male",   "Northern Irish"),
        VctkSpeaker(69,  "p299", "Female", "English"),
        VctkSpeaker(70,  "p300", "Female", "English"),
        VctkSpeaker(71,  "p301", "Female", "English"),
        VctkSpeaker(72,  "p302", "Male",   "English"),
        VctkSpeaker(73,  "p303", "Female", "English"),
        VctkSpeaker(74,  "p304", "Male",   "English"),
        VctkSpeaker(75,  "p305", "Female", "English"),
        VctkSpeaker(76,  "p306", "Female", "English"),
        VctkSpeaker(77,  "p307", "Female", "English"),
        VctkSpeaker(78,  "p308", "Female", "English"),
        VctkSpeaker(79,  "p310", "Female", "English"),
        VctkSpeaker(80,  "p311", "Male",   "English"),
        VctkSpeaker(81,  "p312", "Female", "English"),
        VctkSpeaker(82,  "p313", "Male",   "English"),
        VctkSpeaker(83,  "p314", "Female", "English"),
        VctkSpeaker(84,  "p316", "Male",   "English"),
        VctkSpeaker(85,  "p317", "Female", "English"),
        VctkSpeaker(86,  "p318", "Female", "English"),
        VctkSpeaker(87,  "p323", "Female", "English"),
        VctkSpeaker(88,  "p326", "Male",   "English"),
        VctkSpeaker(89,  "p329", "Female", "English"),
        VctkSpeaker(90,  "p330", "Female", "English"),
        VctkSpeaker(91,  "p333", "Male",   "English"),
        VctkSpeaker(92,  "p334", "Male",   "English"),
        VctkSpeaker(93,  "p335", "Female", "English"),
        VctkSpeaker(94,  "p336", "Male",   "English"),
        VctkSpeaker(95,  "p339", "Female", "English"),
        VctkSpeaker(96,  "p340", "Male",   "English"),
        VctkSpeaker(97,  "p341", "Male",   "English"),
        VctkSpeaker(98,  "p343", "Male",   "English"),
        VctkSpeaker(99,  "p345", "Male",   "English"),
        VctkSpeaker(100, "p347", "Male",   "English"),
        VctkSpeaker(101, "p351", "Female", "English"),
        VctkSpeaker(102, "p360", "Male",   "English"),
        VctkSpeaker(103, "p361", "Female", "English"),
        VctkSpeaker(104, "p362", "Male",   "English"),
        VctkSpeaker(105, "p363", "Male",   "English"),
        VctkSpeaker(106, "p364", "Male",   "English"),
        VctkSpeaker(107, "p374", "Male",   "English"),
        VctkSpeaker(108, "p376", "Male",   "English"),
    )

    private val bySid = speakers.associateBy { it.sid }

    fun forSid(sid: Int): VctkSpeaker? = bySid[sid]

    fun displayLabel(sid: Int): String =
        forSid(sid)?.displayLabel ?: "Speaker $sid"

    /** Returns the distinct set of accent labels across all speakers. */
    val accents: List<String> = speakers.map { it.accent }.distinct().sorted()
}
