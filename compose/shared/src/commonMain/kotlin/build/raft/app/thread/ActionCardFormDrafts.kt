package build.raft.app.thread

import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecChoiceField
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.ActionCardSpecKnownField
import build.raft.app.data.ActionCardSpecPickerField
import build.raft.app.data.ActionCardSpecSelectField
import build.raft.app.data.ActionCardSpecTextAreaField
import build.raft.app.data.ActionCardSpecTextInputField
import build.raft.app.data.ActionCardSpecToggleField
import build.raft.app.matches
import build.raft.app.settings.matches
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// A form sheet's local draft (web `store/actionCardFormDrafts.ts`, question-card spec §5.3, D10):
// what the person has filled in so far and the page they were on, kept in memory for this session
// under the signed-in person, the card's message and its form, so closing the sheet and opening it
// again picks up where they stopped. Only on this device: nothing is sent, nobody else sees it.
// Kept for a form with steps and for a question card's form; cleared on a successful submit and
// once the card has ended.

internal const val ACTION_CARD_FORM_DRAFT_PREFIX: String = "raft.actionCardFormDraft.v1."

internal data class ActionCardFormDraft(
    /** The page the person was on (0-based). */
    val page: Int,
    val values: Map<String, ActionCardFormValue>,
)

private object ActionCardFormDraftStore {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, ActionCardFormDraft>()

    fun read(key: String): ActionCardFormDraft? =
        synchronized(lock) { entries[key]?.copyDeep() }

    fun write(key: String, draft: ActionCardFormDraft): Boolean {
        if (draft.page < 0) return false
        synchronized(lock) { entries[key] = draft.copyDeep() }
        return true
    }

    fun remove(key: String) {
        synchronized(lock) { entries.remove(key) }
    }

    fun removeWhere(matches: (rest: String) -> Boolean) {
        synchronized(lock) {
            val victims =
                entries.keys.filter { key ->
                    key.startsWith(ACTION_CARD_FORM_DRAFT_PREFIX) &&
                        matches(key.removePrefix(ACTION_CARD_FORM_DRAFT_PREFIX))
                }
            victims.forEach { entries.remove(it) }
        }
    }

    fun clearAll() {
        synchronized(lock) { entries.clear() }
    }
}

private fun ActionCardFormValue.copyDeep(): ActionCardFormValue =
    when (this) {
        is ActionCardFormValue.Text -> this
        is ActionCardFormValue.Toggle -> this
        is ActionCardFormValue.Ids -> ActionCardFormValue.Ids(ids.toList())
        is ActionCardFormValue.Choice -> ActionCardFormValue.Choice(selected.toList(), other)
    }

private fun ActionCardFormDraft.copyDeep(): ActionCardFormDraft =
    ActionCardFormDraft(
        page = page,
        values = values.mapValues { (_, value) -> value.copyDeep() },
    )

/** The storage key of one person's draft of one form of one card; null when nobody is signed in (no draft). */
internal fun actionCardFormDraftKey(
    userId: String?,
    messageId: String,
    formId: String,
): String? {
    val user = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val msg = messageId.trim().takeIf { it.isNotEmpty() } ?: return null
    val form = formId.trim().takeIf { it.isNotEmpty() } ?: return null
    return "$ACTION_CARD_FORM_DRAFT_PREFIX$user.$msg.$form"
}

/** The draft under [key], or null (none). */
internal fun readActionCardFormDraft(key: String): ActionCardFormDraft? = ActionCardFormDraftStore.read(key)

/** Keeps the draft for this session; returns true once stored. */
internal fun writeActionCardFormDraft(key: String, draft: ActionCardFormDraft): Boolean = ActionCardFormDraftStore.write(key, draft)

/** Removes one draft by [key]. */
internal fun removeActionCardFormDraft(key: String) = ActionCardFormDraftStore.remove(key)

/** Removes every form draft of a card, whoever's (it ended: answered, expired, withdrawn, closed). */
internal fun clearActionCardFormDrafts(messageId: String) {
    val cleanId = messageId.trim().takeIf { it.isNotEmpty() } ?: return
    ActionCardFormDraftStore.removeWhere { rest -> rest.split('.').getOrNull(1) == cleanId }
}

/** Removes every form draft in this session: signing out, or another account signed in. */
internal fun forgetActionCardFormDrafts() = ActionCardFormDraftStore.clearAll()

/**
 * A draft's value for [field] when it still has [field]'s shape (web `draftFieldValue`), else null
 * so the field starts from its initial default.
 */
internal fun actionCardDraftFieldValue(
    field: ActionCardSpecKnownField,
    value: ActionCardFormValue?,
): ActionCardFormValue? =
    when (field) {
        is ActionCardSpecTextInputField,
        is ActionCardSpecTextAreaField,
        is ActionCardSpecSelectField -> value as? ActionCardFormValue.Text
        is ActionCardSpecToggleField -> value as? ActionCardFormValue.Toggle
        is ActionCardSpecChoiceField -> (value as? ActionCardFormValue.Choice)?.copyDeep()
        is ActionCardSpecPickerField -> (value as? ActionCardFormValue.Ids)?.copyDeep()
    }

/**
 * Restores [draft] against [form] (web `useActionCardFormState`): each field keeps its drafted
 * value when it still has that field's shape (else the form's default), and the page is clamped
 * into range.
 */
internal fun restoreActionCardFormDraft(
    form: ActionCardSpecForm,
    draft: ActionCardFormDraft,
): ActionCardFormDraft {
    val initial = initialActionCardFormValues(form)
    val pages = actionCardFormPages(form)
    val known = form.fields.filterIsInstance<ActionCardSpecKnownField>()
    val values =
        known.associate { field ->
            field.name to (actionCardDraftFieldValue(field, draft.values[field.name]) ?: initial.getValue(field.name))
        }
    return ActionCardFormDraft(
        page = draft.page.coerceIn(0, pages.lastIndex),
        values = values,
    )
}

/** Value equality matching web `sameValue`: picker / choice id lists ignore order; other text is exact. */
internal fun sameActionCardFormValue(a: ActionCardFormValue?, b: ActionCardFormValue?): Boolean =
    when {
        a === b -> true
        a is ActionCardFormValue.Ids && b is ActionCardFormValue.Ids ->
            a.ids.size == b.ids.size && a.ids.all { it in b.ids }
        a is ActionCardFormValue.Choice && b is ActionCardFormValue.Choice ->
            a.selected.size == b.selected.size && a.selected.all { it in b.selected } && a.other == b.other
        else -> a == b
    }

/** Whether [a] and [b] hold the same value for every known field of [form]. */
internal fun sameActionCardFormValues(
    form: ActionCardSpecForm,
    a: Map<String, ActionCardFormValue>,
    b: Map<String, ActionCardFormValue>,
): Boolean =
    form.fields.filterIsInstance<ActionCardSpecKnownField>().all { field ->
        sameActionCardFormValue(a[field.name], b[field.name])
    }
