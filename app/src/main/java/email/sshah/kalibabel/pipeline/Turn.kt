package email.sshah.kalibabel.pipeline

import kotlinx.serialization.Serializable

@Serializable
enum class Direction { A_TO_B, B_TO_A, AUTO }

@Serializable
enum class TurnStage { LISTENING, TRANSCRIBING, TRANSLATING, SPEAKING, DONE, ERROR, CANCELLED }

/** Latency breakdown for one turn, in milliseconds. */
@Serializable
data class TurnMetrics(
    /** End of speech → final transcript (STT latency as felt by the user). */
    val sttMs: Long? = null,
    /** Final transcript → first translated token. */
    val firstTokenMs: Long? = null,
    /** Final transcript → translation complete. */
    val translateMs: Long? = null,
    /** Final transcript → first audio sample played. */
    val firstAudioMs: Long? = null,
    /** End of speech → first audio sample played: the number that matters. */
    val mouthToEarMs: Long? = null,
)

@Serializable
data class Turn(
    val id: Long,
    val direction: Direction,
    val sourceTag: String? = null,
    val targetTag: String? = null,
    val original: String = "",
    /** True while [original] is still a live, changing partial transcript. */
    val partial: Boolean = false,
    val translation: String = "",
    val stage: TurnStage = TurnStage.LISTENING,
    val error: String? = null,
    /** A non-fatal note, e.g. "translated on-device (network error)". */
    val note: String? = null,
    val metrics: TurnMetrics = TurnMetrics(),
    val route: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean
        get() = stage == TurnStage.LISTENING || stage == TurnStage.TRANSCRIBING ||
            stage == TurnStage.TRANSLATING || stage == TurnStage.SPEAKING
}

data class InterpreterState(
    val turns: List<Turn> = emptyList(),
    /** Which mic is open, or null. */
    val listening: Direction? = null,
    val level: Float = 0f,
    val status: String? = null,
    val error: String? = null,
    val speakingTurn: Long? = null,
)
