package ai.crossmeeting.app.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * As respostas reais do Deepgram (e do enhance-transcript) têm muito mais campos do que os
 * data classes abaixo modelam (channel_index, duration, speech_final, metadata, confidence,
 * words, etc.) — o Json padrão do kotlinx.serialization rejeita chaves desconhecidas por
 * padrão, o que fazia o decode falhar silenciosamente (a exceção era engolida pelo
 * runCatching) e o estado da transcrição nunca era atualizado.
 */
val LenientJson = Json { ignoreUnknownKeys = true }

@Serializable
data class DeepgramTokenResponse(
    val token: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    val error: String? = null,
)

@Serializable
data class DeepgramAlternative(val transcript: String = "")

@Serializable
data class DeepgramChannel(val alternatives: List<DeepgramAlternative> = emptyList())

@Serializable
data class DeepgramMessage(
    val type: String? = null,
    val channel: DeepgramChannel? = null,
    @SerialName("is_final") val isFinal: Boolean = false,
)

@Serializable
data class EnhanceTranscriptRequest(
    val transcript: String,
    @kotlinx.serialization.SerialName("meetingId") val meetingId: Long? = null,
)

@Serializable
data class ActionItem(val text: String = "", val owner: String? = null, val due: String? = null)

@Serializable
data class Decision(val text: String = "", val status: String? = null)

@Serializable
data class Enhancement(
    val title: String? = null,
    val summary: String? = null,
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val decisions: List<Decision> = emptyList(),
)

@Serializable
data class EnhanceTranscriptResponse(
    val enhancement: Enhancement? = null,
    val error: String? = null,
)
