package build.raft.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * An [ActionCardSpecCard] written back as the contract's JSON: the card as the shared read parse
 * returns it (defaults filled in, unknown keys / icons / locales dropped, `unsupported` markers),
 * or after strip (`open_web`): what the vector's `parsed` / `strip` hold. Not a wire format: a card
 * with `unsupported` or `open_web` markers is refused when read again, so the shared store keeps
 * the server's JSON instead.
 */

internal fun ActionCardSpecCard.toJsonObject(): JsonObject =
    buildJsonObject {
        put("catalog", catalog)
        icon?.let { put("icon", it.wire) }
        put("title", title.toJsonObject())
        put("spec", spec.toJsonObject())
        confirms?.let { map -> put("confirms", JsonObject(map.mapValues { (_, confirm) -> confirm.toJsonObject() })) }
        forms?.let { map -> put("forms", JsonObject(map.mapValues { (_, form) -> form.toJsonObject() })) }
        result?.let { put("result", it.toJsonObject()) }
    }

internal fun ActionCardSpecMsg.toJsonObject(): JsonObject =
    buildJsonObject {
        put("key", key)
        put("text", JsonObject(text.mapValues { (_, template) -> JsonPrimitive(template) }))
        params?.let { map -> put("params", JsonObject(map.mapValues { (_, param) -> param.toJsonElement() })) }
    }

internal fun ActionCardSpecMsgParam.toJsonElement(): JsonElement =
    when (this) {
        is ActionCardSpecMsgParam.Text -> JsonPrimitive(value)
        is ActionCardSpecMsgParam.Integer -> JsonPrimitive(value)
        is ActionCardSpecMsgParam.Entity -> ref.toJsonObject()
    }

internal fun ActionCardSpecEntityRef.toJsonObject(): JsonObject =
    buildJsonObject {
        put("kind", kind.wire)
        put("id", id)
        serverId?.let { put("serverId", it) }
        put("fallback", fallback)
    }

internal fun ActionCardMsgSegment.toJsonObject(): JsonObject =
    when (this) {
        is ActionCardMsgSegment.Text -> buildJsonObject { put("text", text) }
        is ActionCardMsgSegment.Ref -> buildJsonObject { put("ref", ref.toJsonObject()) }
    }

internal fun ActionCardFormIssue.toJsonObject(): JsonObject =
    buildJsonObject {
        put("field", field?.let(::JsonPrimitive) ?: JsonNull)
        put("code", code.wire)
        put("message", message.toJsonObject())
    }

private fun ActionCardSpecContent.toJsonElement(): JsonElement =
    when (this) {
        is ActionCardSpecContent.Plain -> JsonPrimitive(text)
        is ActionCardSpecContent.Message -> msg.toJsonObject()
    }

private fun ActionCardSpecTree.toJsonObject(): JsonObject =
    buildJsonObject {
        put("root", root)
        put("elements", JsonObject(elements.mapValues { (_, element) -> element.toJsonObject() }))
    }

private fun ActionCardSpecConfirm.toJsonObject(): JsonObject =
    buildJsonObject {
        put("title", title.toJsonObject())
        put("confirmLabel", confirmLabel.toJsonObject())
        put("spec", spec.toJsonObject())
    }

private fun element(type: String, props: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("type", type)
        put("props", buildJsonObject(props))
    }

private fun ActionCardSpecElement.toJsonObject(): JsonObject =
    when (this) {
        is CardStack ->
            buildJsonObject {
                put("type", "Stack")
                put("props", buildJsonObject { put("direction", direction.wire); put("wrap", wrap) })
                children?.let { ids -> put("children", JsonArray(ids.map(::JsonPrimitive))) }
            }
        is CardRelativeTime -> element("RelativeTime") { put("at", at) }
        is CardDuration -> element("Duration") { put("start", start); end?.let { put("end", it) } }
        is ActionCardSpecDisplayElement.Text ->
            element("Text") {
                put("text", text.toJsonElement())
                put("tone", tone.wire)
                put("mono", mono)
                clamp?.let { put("clamp", it) }
            }
        is ActionCardSpecDisplayElement.Badge -> element("Badge") { put("text", text.toJsonElement()); put("tone", tone.wire) }
        is ActionCardSpecDisplayElement.Field ->
            element("Field") {
                put("label", label.toJsonObject())
                put(
                    "value",
                    when (val value = value) {
                        is ActionCardSpecFieldValue.Plain -> JsonPrimitive(value.text)
                        is ActionCardSpecFieldValue.Message -> value.msg.toJsonObject()
                        is ActionCardSpecFieldValue.Entities -> JsonArray(value.refs.map { it.toJsonObject() })
                    },
                )
                mono?.let { put("mono", it) }
                muted?.let { put("muted", it) }
                breakAll?.let { put("breakAll", it) }
            }
        is ActionCardSpecDisplayElement.EntityRef -> buildJsonObject { put("type", "EntityRef"); put("props", ref.toJsonObject()) }
        is ActionCardSpecDisplayElement.ScopeList ->
            element("ScopeList") {
                put("scopes", JsonArray(scopes.map(::JsonPrimitive)))
                agentName?.let { put("agentName", it) }
            }
        is ActionCardSpecDisplayElement.Notice -> element("Notice") { put("text", text.toJsonObject()); put("tone", tone.wire) }
        is ActionCardSpecButton ->
            element("Button") {
                put("label", label.toJsonObject())
                put("emphasis", emphasis.wire)
                put("action", action.toJsonObject())
                disabledReason?.let { put("disabledReason", it.toJsonObject()) }
            }
    }

private fun ActionCardSpecAction.toJsonObject(): JsonObject =
    buildJsonObject {
        when (val action = this@toJsonObject) {
            is ActionCardSpecAction.Execute -> {
                put("kind", "execute")
                action.confirm?.let { put("confirm", it) }
            }
            is ActionCardSpecAction.OpenForm -> {
                put("kind", "open_form")
                put("form", action.form)
            }
            is ActionCardSpecAction.Open -> {
                put("kind", "open")
                put("intent", action.intent.wire)
                action.gate?.let { put("gate", it) }
            }
            is ActionCardSpecAction.Navigate -> {
                put("kind", "navigate")
                put(
                    "target",
                    buildJsonObject {
                        put("kind", action.target.kind.wire)
                        put("id", action.target.id)
                        action.target.channelId?.let { put("channelId", it) }
                        action.target.serverId?.let { put("serverId", it) }
                    },
                )
            }
            is ActionCardSpecAction.NavigateUrl -> {
                put("kind", "navigate")
                put("url", action.url)
            }
            is ActionCardSpecAction.Unsupported -> {
                put("kind", "unsupported")
                put("originalKind", action.originalKind)
                action.originalIntent?.let { put("originalIntent", it) }
            }
            ActionCardSpecAction.OpenInWeb -> put("kind", "open_web")
        }
    }

private fun ActionCardSpecForm.toJsonObject(): JsonObject =
    buildJsonObject {
        put("title", title.toJsonObject())
        put("submitLabel", submitLabel.toJsonObject())
        description?.let { put("description", it.toJsonObject()) }
        put("fields", JsonArray(fields.map { it.toJsonObject() }))
    }

private fun ActionCardSpecFormField.toJsonObject(): JsonObject =
    buildJsonObject {
        val field = this@toJsonObject
        if (field is ActionCardSpecFormField.Unsupported) {
            put("type", "unsupported")
            put("originalType", field.originalType)
            put("name", field.name)
            return@buildJsonObject
        }
        field as ActionCardSpecKnownField
        put("name", field.name)
        put("label", field.label.toJsonObject())
        field.placeholder?.let { put("placeholder", it.toJsonObject()) }
        field.description?.let { put("description", it.toJsonObject()) }
        when (field) {
            is ActionCardSpecTextInputField -> {
                put("type", "TextInput")
                field.required?.let { put("required", it) }
                put("maxLength", field.maxLength)
                field.default?.let { put("default", it) }
            }
            is ActionCardSpecTextAreaField -> {
                put("type", "TextArea")
                field.required?.let { put("required", it) }
                put("maxLength", field.maxLength)
                field.rows?.let { put("rows", it) }
                field.default?.let { put("default", it) }
            }
            is ActionCardSpecSelectField -> {
                put("type", "Select")
                field.required?.let { put("required", it) }
                put("options", JsonArray(field.options.map { option -> buildJsonObject { put("value", option.value); put("label", option.label.toJsonObject()) } }))
                field.default?.let { put("default", it) }
            }
            is ActionCardSpecToggleField -> {
                put("type", "Toggle")
                field.default?.let { put("default", it) }
            }
            is ActionCardSpecPickerField -> {
                when (field) {
                    is ActionCardSpecMemberPickerField -> {
                        put("type", "MemberPicker")
                        put("kinds", JsonArray(field.kinds.map { JsonPrimitive(it.wire) }))
                        put("scope", field.scope.wire)
                    }
                    is ActionCardSpecChannelPickerField -> {
                        put("type", "ChannelPicker")
                        put("scope", field.scope.wire)
                    }
                    is ActionCardSpecAgentPickerField -> put("type", "AgentPicker")
                }
                field.required?.let { put("required", it) }
                field.emptyText?.let { put("emptyText", it.toJsonObject()) }
                put("multiple", field.multiple)
                field.maxItems?.let { put("maxItems", it) }
                field.default?.let { refs -> put("default", JsonArray(refs.map { it.toJsonObject() })) }
            }
        }
    }
