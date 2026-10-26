package build.raft.app

import build.raft.app.data.*


/**
 * The feedback form of slock `actionCard.v1.vector.json` (cards[2], S0 #11226): all seven field
 * types. Picker defaults point at the member picker roster so the fixtures show them picked.
 */
internal object ActionCardFormTestFixtures {
    private fun msg(key: String, en: String, zhCn: String? = null, params: Map<String, ActionCardSpecMsgParam> = emptyMap()) =
        ActionCardSpecMsg(key = "actionCard.feedbackSubmit.$key", text = listOfNotNull("en" to en, zhCn?.let { "zh-cn" to it }).toMap(), params = params)

    val form =
        ActionCardSpecForm(
            title = msg("form.title", "Send feedback to Raft", "向 Raft 发送反馈"),
            submitLabel = msg("form.submit", "Send", "发送"),
            description =
                msg(
                    "form.description",
                    "Sent as {user}; you can edit everything first.",
                    params = mapOf("user" to ActionCardSpecMsgParam.Entity(ActionCardSpecEntityRef(ActionCardSpecEntityKind.User, "user-artin", fallback = "alice"))),
                ),
            fields =
                listOf(
                    ActionCardSpecTextInputField(
                        name = "summary",
                        label = msg("form.summary.label", "Summary"),
                        placeholder = msg("form.summary.placeholder", "One line"),
                        required = true,
                        maxLength = 80,
                        default = "Export does nothing on Safari",
                    ),
                    ActionCardSpecTextAreaField(
                        name = "message",
                        label = msg("form.message.label", "Message"),
                        description = msg("form.message.description", "What happened, and what you expected."),
                        required = true,
                        maxLength = 4000,
                        rows = 6,
                        default = "The export button does nothing on Safari 17.",
                    ),
                    ActionCardSpecSelectField(
                        name = "feedbackType",
                        label = msg("form.type.label", "Type"),
                        required = true,
                        options =
                            listOf(
                                ActionCardSpecSelectOption("idea", msg("type.idea", "Idea", "建议")),
                                ActionCardSpecSelectOption("problem", msg("type.problem", "Problem", "问题")),
                            ),
                        default = "problem",
                    ),
                    ActionCardSpecToggleField(name = "contactMe", label = msg("form.contact.label", "Raft may contact me"), default = false),
                    ActionCardSpecMemberPickerField(
                        name = "cc",
                        label = msg("form.cc.label", "Also notify"),
                        emptyText = msg("form.cc.empty", "No one else is in this channel."),
                        multiple = true,
                        maxItems = 5,
                        default =
                            listOf(
                                ActionCardSpecEntityRef(ActionCardSpecEntityKind.User, "user-designer", fallback = "bob"),
                                ActionCardSpecEntityRef(ActionCardSpecEntityKind.Agent, "agent-cindy", fallback = "Kirby"),
                            ),
                        kinds = listOf(ActionCardSpecMemberKind.Human, ActionCardSpecMemberKind.Agent),
                        scope = ActionCardSpecMemberScope.Channel,
                    ),
                    ActionCardSpecChannelPickerField(
                        name = "postIn",
                        label = msg("form.postIn.label", "Post a copy in"),
                        multiple = false,
                        default = listOf(ActionCardSpecEntityRef(ActionCardSpecEntityKind.Channel, "channel-general", fallback = "general")),
                        scope = ActionCardSpecChannelScope.Joined,
                    ),
                    ActionCardSpecAgentPickerField(name = "owner", label = msg("form.owner.label", "Follow-up agent"), multiple = false, maxItems = 1),
                ),
        )

    private fun channel(id: String, name: String, kind: RaftForwardTargetVisualKind = RaftForwardTargetVisualKind.PublicChannel) =
        RaftForwardTargetPickerItem(stableKey = id, title = name, subtitle = "", visualKind = kind, payload = id)

    val candidates =
        ActionCardFormCandidates(
            serverMembers = MemberPickerTestFixtures.roster,
            channelMembers = MemberPickerTestFixtures.roster.filter { it.id.value != "user-bo" },
            joinedChannels =
                listOf(
                    channel("channel-general", "general"),
                    channel("channel-design", "design"),
                    channel("channel-launch", "launch-week", RaftForwardTargetVisualKind.PrivateChannel),
                    channel("channel-partners", "partners", RaftForwardTargetVisualKind.JointChannel),
                ),
        )

    /** Nothing entered: blank text, no option, the toggle off, no picks. */
    val empty: Map<String, ActionCardFormValue> =
        form.fields.associate { field ->
            field.name to
                when (field) {
                    is ActionCardSpecToggleField -> ActionCardFormValue.Toggle(false)
                    is ActionCardSpecPickerField -> ActionCardFormValue.Ids(emptyList())
                    else -> ActionCardFormValue.Text("")
                }
        }

    /** The defaults, the toggle on and a follow-up agent picked. */
    val filled: Map<String, ActionCardFormValue> =
        initialActionCardFormValues(form) +
            mapOf("contactMe" to ActionCardFormValue.Toggle(true), "owner" to ActionCardFormValue.Ids(listOf("agent-lagoon")))

    /** A 422 answer: an issue under every field that can have one, and one for the whole form. */
    val serverIssues: List<ActionCardFormIssue> =
        listOf(
            actionCardFormIssue("summary", ActionCardFormIssueCode.Required),
            actionCardFormIssue("message", ActionCardFormIssueCode.TooLong, mapOf("max" to ActionCardSpecMsgParam.Integer(4000))),
            actionCardFormIssue("feedbackType", ActionCardFormIssueCode.InvalidOption),
            actionCardFormIssue("contactMe", ActionCardFormIssueCode.InvalidFormat),
            actionCardFormIssue("cc", ActionCardFormIssueCode.NotAllowed),
            actionCardFormIssue("postIn", ActionCardFormIssueCode.NotFound),
            actionCardFormIssue("owner", ActionCardFormIssueCode.Required),
            actionCardFormIssue(null, ActionCardFormIssueCode.Conflict),
        )
}
