package ai.crossmeeting.app.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatAskRequest(val question: String, val history: List<ChatMessage> = emptyList())

@Serializable
data class ChatAskResponse(val answer: String? = null, val error: String? = null)
