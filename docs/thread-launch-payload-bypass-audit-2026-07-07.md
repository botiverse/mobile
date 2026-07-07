# Thread Launch Payload Bypass Audit - 2026-07-07

Task: #422, link-convergence t8 prep.

Scope for this prep pass:

- Identify production code that hand-builds `WireThreadLaunchPayload` or round-trips `KuiklyThreadScreenPayload` into it outside the canonical Home/Activity open path.
- Pin current behavior with tests where coverage was missing.
- Do not migrate implementation yet. Actual bypass removal waits for #420 and #421 to land the final bridge/open assembly boundaries.

Launch-contract boundary:

- Production route/pageData may carry identity and intent only.
- Product/display state must remain loaded by destination stores/repositories.
- Current tests should protect existing identity-only behavior until the later migration changes the construction path.

## Bypass Inventory

| ID | Current production entry | Current behavior | Existing/new coverage | Migration note |
|---|---|---|---|---|
| B1 | `SlockHomeComponents.kt` `buildHomeConversationThreadLaunchPayload(...)` | Builds a thread launch directly from `SlockHomePayload` fixture/home conversation rows. It maps `"dm"` to direct message, `"thread"` to reply thread, everything else to channel, and preserves joint via `homeChannelKind()`. | Existing `SlockHomeComponentsTest.homeConversationLaunchCarriesOnlyConversationIdentity`, `homeDirectMessageLaunchDoesNotInventParentPair`, and `homeJointChannelLaunchCarriesChannelKind`. | Likely legacy/visual home route helper. Migrate to canonical conversation identity/open request once home fixture rows expose the same identity type, or keep quarantined as fixture-only if not production. |
| B2 | `SettingsHomePage.kt` `buildStandaloneTaskThreadLaunchPayload(...)` | Task row opens a reply thread using `task.channelId`, `task.parentMessageId ?: task.messageId`, optional `task.threadId`, and focuses the task message when distinct from the parent. Parent is currently forced to channel. | Existing `ThreadPayloadTest.standaloneTaskThreadLaunchCarriesOnlyIdentityAndIntent`. | After #420/#421, route through the single open assembly using a task-derived identity. Needs a decision for tasks in DM threads before removing the channel parent default. |
| B3 | `MembersPage.kt` `buildMembersDirectMessageThreadLaunchPayload(...)` | Members agent detail Message action opens a direct-message conversation from `HomeDirectMessageRow.threadId ?: channelId`. | Existing `MembersDirectMessageLaunchTest.agentDetailMessageActionBuildsDirectMessageLaunchPayload` and fallback test. | Convert `HomeDirectMessageRow` to the canonical direct-message conversation identity/open request. |
| B4 | `MembersPage.kt` `SlockThreadWebLinkTarget.toMembersReminderThreadLaunchPayload(...)` | Members reminder permalink opens channel/DM messages and thread replies. Thread permalinks use `threadParentMessageId`; DM route preserves direct-message parent kind. | New `MembersDirectMessageLaunchTest.membersReminderThreadPermalinkBuildsChannelReplyLaunchPayload`, `membersReminderDmThreadPermalinkPreservesDirectMessageParentKind`, and `membersReminderDmPermalinkBuildsDirectMessageLaunchPayload`. | Migrate permalink targets to the single open assembly. This should share the same channel/DM parent-kind semantics as markdown link targets. |
| B5 | `SharedThreadStore.kt` `buildMarkdownChannelLaunchPayload(...)` | Markdown `#channel` links resolve a unique `ConversationSummary` from `HomeSnapshot`, then launch channel/joint identity with current thread UI intent flags. | Existing `ThreadPayloadTest.markdownChannelLaunchPayloadResolvesChannelNameToIdentityOnlyRoute`, joint-kind, and unknown/ambiguous tests. | Convert resolved `ConversationSummary` to the canonical open request/payload builder, keeping markdown-specific rejection of ambiguous labels. |
| B6 | `SharedThreadStore.kt` `buildMarkdownThreadRefLaunchPayload(...)` | Markdown `#channel:thread` links resolve the parent channel, use explicit parent message/thread ids from the parsed link, force reply-thread surface, and carry current UI intent flags. | Existing `ThreadPayloadTest.markdownThreadRefLaunchPayloadResolvesChannelThreadToIdentityOnlyRoute`. | Route through the canonical reply-thread identity builder. Needs care because `threadShortId` may be only a fallback instance id when the real thread channel id is absent. |
| B7 | `ThreadPage.kt` `KuiklyThreadScreenPayload.markdownTaskNumberLaunchPayload(...)` | Markdown task-number links resolve a `TaskSummary` to a reply-thread launch. Parent is `task.parentMessageId ?: task.messageId`; focus is task message when distinct; current UI intent flags are preserved. | Existing `ThreadPayloadTest.markdownTaskNumberLaunchPayloadUsesTaskIdentityOnly` and focus-distinct test. | Share task-derived identity logic with B2 so task row and markdown task open cannot diverge. |
| B8 | `ThreadRoutePayload.kt` `KuiklyThreadScreenPayload.toWireThreadLaunchPayload()` | Page-internal round-trip strips display/product state and keeps route identity, composer intent, time context, and action affordances. Used by ThreadPage standalone open and tests. | Existing `ThreadPayloadTest.screenPayloadWireLaunchDropsDisplayAndProductState`, pageData identity-only tests, and route/composer tests. | This may remain as the bridge compatibility boundary after #420, but all call sites should be audited so production opens prefer canonical open request before needing a payload round-trip. When t8收编 removes this bypass, migrate markdown/round-trip `toInitialPayload` call sites to `toInitialPayloadOrNull` with explicit failure handling so the current `error()` compatibility path disappears with the bypass. |

## Migration Mapping And Risks

This section is the handoff list for the future convergence PRs. "Future open assembly" means the single #420/#421 boundary once it exists; this prep PR intentionally does not introduce or call that boundary.

| ID | Fields to map into future open assembly | Risks / checks for migration |
|---|---|---|
| B1 | `SlockHomePayload.channelId`, `threadId`, `parentMessageId`, focused message intent, and current `homeChannelKind()` / conversation kind should become a canonical conversation identity plus open intent. | `SlockHomeComponents:506` is partly aligned by #249 because kind is already contract-shaped; preserve joint-vs-channel and DM behavior. Decide whether this remains fixture/visual-only before touching production route code. |
| B2 | `TaskSummary.channelId`, `threadId`, `parentMessageId ?: messageId`, `messageId`, and time context should become one task-derived reply-thread open identity. | Current code forces parent kind to channel. Before migration, confirm whether task summaries can represent DM-thread tasks; if yes, the new boundary must not keep the silent channel default. |
| B3 | `HomeDirectMessageRow.threadId ?: channelId` should become a direct-message conversation identity. | The fallback is current behavior and may hide incomplete row projection. Keep member/profile display data out of the launch payload; the destination must still load through thread/member stores. |
| B4 | `SlockThreadWebLinkTarget.channelId`, `messageId`, `routeKind`, and `threadParentMessageId` should become a parsed-permalink open identity; thread links become reply-thread opens, non-thread DM links become direct-message opens. | Preserve DM-thread parent kind exactly; this is the gap the new tests pin. Also keep permalink link parsing behavior separate from destination store loading. |
| B5 | Resolved unique `ConversationSummary` from `HomeSnapshot` should map to channel/joint conversation identity plus the caller's current UI intent flags. | Unknown or ambiguous markdown channel labels must still reject instead of opening a guessed channel. Preserve joint channel kind and avoid routing `ConversationSummary` display fields. |
| B6 | Parsed markdown thread ref parent channel, explicit parent message/thread ids, and current UI intent flags should map to a reply-thread identity. | `threadShortId` can be only a fallback instance/thread hint when no real auxiliary stream id is known; migration must not confuse the short id with canonical stream identity. |
| B7 | `TaskSummary` fields should map through the same task-derived open identity as B2, then merge the current thread payload's intent/time/action flags. | Keep B2 and B7 in one convergence PR or with shared tests so task-row open and markdown task-number open cannot diverge on parent/focus semantics. |
| B8 | `KuiklyThreadScreenPayload` identity/intent fields should remain convertible to `WireThreadLaunchPayload` only at the Kuikly bridge compatibility edge if still needed after #420. | Do not delete this blindly: it currently enforces stripping display/product state. First audit every production caller and keep descriptor/pageData guards proving messages/tasks/member data stay out of launch payloads. |

## Suggested PR Split After #420/#421

1. **Task and markdown convergence**: migrate B2 and B7 together first, because both derive a reply-thread launch from `TaskSummary`.
2. **Permalink/link convergence**: migrate B4, B5, and B6 together or in two PRs, because all start from parsed links and must preserve channel/DM parent semantics.
3. **Home/Members/round-trip cleanup**: migrate B1 and B3, then decide whether B8 remains only as the Kuikly bridge compatibility boundary.

Each PR should include:

- A before/after test proving the new path preserves the current route identity.
- A launch-contract assertion that no message/task/member/display state enters `compose_thread` pageData.
- A short note for @LiBai review describing which bypass was removed and which remaining bypasses still exist.
