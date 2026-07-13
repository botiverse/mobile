package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class SettingsQuiverFeedbackDraftSnapshot(
    val text: String = "",
    val attachments: List<PlatformFile> = emptyList()
)

@Serializable
private data class PersistedSettingsQuiverFeedbackDraft(
    val text: String = "",
    val attachments: List<PersistedSettingsQuiverFeedbackAttachment> = emptyList()
)

@Serializable
private data class PersistedSettingsQuiverFeedbackAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val previewUri: String? = null,
    val fileRef: String,
    val source: String = "platform_picker"
)

internal class SettingsQuiverFeedbackDraftStore(
    private val store: SharedPreferenceStore
) {
    fun read(accountId: String): SettingsQuiverFeedbackDraftSnapshot =
        store.getString(key(accountId))
            ?.let { encoded ->
                runCatching {
                    DraftJson.decodeFromString<PersistedSettingsQuiverFeedbackDraft>(encoded)
                }.getOrNull()
            }
            ?.let { persisted ->
                SettingsQuiverFeedbackDraftSnapshot(
                    text = persisted.text,
                    attachments = persisted.attachments.map { attachment -> attachment.toPlatformFile() }
                )
            }
            ?: SettingsQuiverFeedbackDraftSnapshot()

    fun save(
        accountId: String,
        text: String,
        attachments: List<PlatformFile> = emptyList()
    ) {
        val persistedAttachments =
            attachments
                // Picker-backed fileRefs are the only attachment handles that can
                // survive composition/page recreation. Never put file bytes/base64
                // into the shared preference record.
                .mapNotNull { attachment -> attachment.toPersistedFeedbackAttachmentOrNull() }
                .distinctBy { it.id }
                .take(MaxAttachments)
        val persisted =
            PersistedSettingsQuiverFeedbackDraft(
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
        const val KeyPrefix = "settingsQuiverFeedbackDraft:v1:"
        const val AnonymousAccount = "anonymous"
        const val MaxAttachments = 9
        val DraftJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private fun PlatformFile.toPersistedFeedbackAttachmentOrNull(): PersistedSettingsQuiverFeedbackAttachment? {
    val durableFileRef = fileRef?.takeIf { it.isNotBlank() } ?: return null
    return PersistedSettingsQuiverFeedbackAttachment(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = previewUri,
        fileRef = durableFileRef,
        source = source
    )
}

private fun PersistedSettingsQuiverFeedbackAttachment.toPlatformFile(): PlatformFile =
    PlatformFile(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = previewUri,
        fileRef = fileRef,
        source = source
    )
