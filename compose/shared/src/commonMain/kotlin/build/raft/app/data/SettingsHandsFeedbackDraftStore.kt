package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class SettingsHandsFeedbackDraftSnapshot(
    val text: String = "",
    val attachments: List<PlatformFile> = emptyList()
)

@Serializable
private data class PersistedSettingsHandsFeedbackDraft(
    val text: String = "",
    val attachments: List<PersistedSettingsHandsFeedbackAttachment> = emptyList()
)

@Serializable
private data class PersistedSettingsHandsFeedbackAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val previewUri: String? = null,
    val fileRef: String,
    val source: String = "platform_picker"
)

internal class SettingsHandsFeedbackDraftStore(
    private val store: SharedPreferenceStore
) {
    fun read(accountId: String): SettingsHandsFeedbackDraftSnapshot =
        store.getString(key(accountId))
            ?.let { encoded ->
                runCatching {
                    DraftJson.decodeFromString<PersistedSettingsHandsFeedbackDraft>(encoded)
                }.getOrNull()
            }
            ?.let { persisted ->
                SettingsHandsFeedbackDraftSnapshot(
                    text = persisted.text,
                    attachments = persisted.attachments.map { attachment -> attachment.toPlatformFile() }
                )
            }
            ?: SettingsHandsFeedbackDraftSnapshot()

    fun save(
        accountId: String,
        text: String,
        attachments: List<PlatformFile> = emptyList()
    ) {
        val persistedAttachments =
            attachments
                // The platform owns file durability; this store persists only the
                // resulting handle and metadata, never file bytes/base64.
                .mapNotNull { attachment -> attachment.toPersistedFeedbackAttachmentOrNull() }
                .distinctBy { it.id }
                .take(MaxAttachments)
        val persisted =
            PersistedSettingsHandsFeedbackDraft(
                text = text.takeIf { it.isNotBlank() }.orEmpty(),
                attachments = persistedAttachments
            )
        store.putString(
            key(accountId),
            persisted
                .takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
                ?.let { value -> DraftJson.encodeToString(value) }
        )
    }

    fun clear(accountId: String) {
        store.putString(key(accountId), null)
    }

    private fun key(accountId: String): String =
        "$KeyPrefix${accountId.trim().ifBlank { AnonymousAccount }}"

    private companion object {
        const val KeyPrefix = "settingsHandsFeedbackDraft:v1:"
        const val AnonymousAccount = "anonymous"
        const val MaxAttachments = 9
        val DraftJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private fun PlatformFile.toPersistedFeedbackAttachmentOrNull(): PersistedSettingsHandsFeedbackAttachment? {
    val durableFileRef = fileRef?.takeIf { it.isNotBlank() } ?: return null
    return PersistedSettingsHandsFeedbackAttachment(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = previewUri,
        fileRef = durableFileRef,
        source = source
    )
}

private fun PersistedSettingsHandsFeedbackAttachment.toPlatformFile(): PlatformFile =
    PlatformFile(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = previewUri,
        fileRef = fileRef,
        source = source
    )
