package ai.crossmeeting.app

import kotlinx.serialization.Serializable

/** Espelha a tabela `spaces` do Postgres (ver Docs/crossmeeting-database.md no repo do desktop). */
@Serializable
data class SpaceRow(
    val id: Long,
    val name: String,
    val emoji: String,
    @kotlinx.serialization.SerialName("is_default") val isDefault: Boolean = false,
)

/** Espelha a tabela `meetings` do Postgres — listagem e home. */
@Serializable
data class MeetingRow(
    val id: Long,
    val title: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("duration_seconds") val durationSeconds: Int = 0,
    @kotlinx.serialization.SerialName("word_count") val wordCount: Int = 0,
    @kotlinx.serialization.SerialName("space_id") val spaceId: Long? = null,
    @kotlinx.serialization.SerialName("deleted_at") val deletedAt: String? = null,
)

/** Espelha a tabela `action_items`. */
@Serializable
data class ActionItemRow(
    val id: Long,
    val text: String,
    val owner: String? = null,
    @kotlinx.serialization.SerialName("due_date") val dueDate: String? = null,
    val status: String = "pendente",
    val prioridade: String = "média",
    @kotlinx.serialization.SerialName("meeting_title") val meetingTitle: String? = null,
    @kotlinx.serialization.SerialName("meeting_id") val meetingId: Long? = null,
    @kotlinx.serialization.SerialName("done_at") val doneAt: String? = null,
)

/** Espelha a tabela `meetings` por completo — usado na tela de detalhe de uma reunião. */
@Serializable
data class MeetingDetailRow(
    val id: Long,
    val title: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("duration_seconds") val durationSeconds: Int = 0,
    @kotlinx.serialization.SerialName("word_count") val wordCount: Int = 0,
    val transcript: String = "",
    val enhancement: String? = null,
)

/**
 * Espelha a tabela `profiles` — `profiles.id` é o UUID que `meetings.user_id`/`spaces.user_id`
 * referenciam de fato (diferente do `auth.uid()` do Supabase Auth; a função `auth_profile_id()`
 * no Postgres faz essa ponte via email, ver Docs/crossmeeting-database.md).
 */
@Serializable
data class ProfileRow(val id: String, val email: String)

/** Só o id — usado para ler de volta o id gerado depois de inserir uma reunião nova. */
@Serializable
data class MeetingIdRow(val id: Long)

/** Payload de update pra mover uma reunião de space ("Mover para..."). */
@Serializable
data class MeetingSpaceUpdate(@kotlinx.serialization.SerialName("space_id") val spaceId: Long?)

/** Payload de insert para uma reunião gravada no app — mesmas colunas que o desktop grava. */
@Serializable
data class NewMeeting(
    val title: String,
    @kotlinx.serialization.SerialName("user_id") val userId: String? = null,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("duration_seconds") val durationSeconds: Int = 0,
    val language: String = "pt-BR",
    @kotlinx.serialization.SerialName("word_count") val wordCount: Int = 0,
    val transcript: String = "",
    val enhancement: String? = null,
)
