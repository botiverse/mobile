# Thread Action Card Confirm Execution Spec

## Context

Thread action cards are visible in KMP now, but the confirm button can look like it does nothing for `agent:create`. The current KMP implementation is not equivalent to React:

- React opens the normal create dialog from the card. The card is a prefilled proposal, not the resource creation flow itself.
- The server intentionally rejects one-click execution for `agent:create` and `channel:add_member` with `409 DIALOG_REQUIRED`.
- KMP currently calls the typed create APIs directly from the Thread card button, then calls `mark-executed` without checking whether that metadata update succeeded.

That means a create-agent card can fail before the user sees a dialog, or create a resource while leaving the card in `prepared` state, and the user only sees a generic toast if the bridge surfaces it.

## Source Contract

React `ActionCard` has one click contract for all action types:

`card button -> open regular dialog -> dialog submit uses regular API -> on success POST /actions/{messageId}/mark-executed -> card metadata updates to Done`.

For `agent:create`, the card action only contains:

- `type = "agent:create"`
- `name`
- `description`
- optional `draftHint`

It must not carry or auto-select the technical runtime fields. The human chooses computer, runtime, model, reasoning effort, env vars, and avatar in `CreateAgentDialog`. The dialog submits the existing `POST /agents` API with the full create body. Only after that returns an agent does the frontend call:

```json
{
  "result": {
    "kind": "agent",
    "id": "<created-agent-id>",
    "name": "<created-agent-name>"
  }
}
```

to `POST /actions/{messageId}/mark-executed`.

The server-side `/actions/{messageId}/mark-executed` endpoint does not create the resource. It only records the already-created result, updates `messages.actionMetadata`, emits `message:updated`, and wakes the requesting agent. If `mark-executed` fails after creation, React logs the error and the resource remains created; the card may stay prepared until refresh.

## Current KMP Gap

KMP currently wires `ThreadActionCardView` button clicks to `commitThreadActionCard`:

- `agent:create` calls `RaftAgentsApiClient.createAgent(...)` directly with the card name/description.
- `channel:create` calls `ChannelRepository.createChannel(...)` directly.
- `channel:add_member` calls `MemberRepository.addChannelMember(...)` directly.
- All three then call `RaftIntegrationsActionsPushApiClient.markActionExecuted(...)`.

This is typed, but it is the wrong interaction shape for dialog-driven action cards. It skips the dialog layer, hides required choices for `agent:create`, and cannot present dialog-level validation errors such as missing computer, unsupported runtime, quota, or duplicate name before submit.

It also treats `markActionExecuted` as fire-and-forget. A successful create followed by failed metadata sync will be reported locally as success, but the card can remain prepared on the next server refresh.

## Desired KMP Contract

KMP should match the React flow:

`Thread card UI -> Store/effect opens typed dialog -> dialog submit calls Repository/API -> success calls mark-executed -> Store patches card metadata`.

The UI must not call repository/API directly. The Thread store or route controller should emit a typed effect such as:

```kotlin
sealed interface ThreadActionCardEffect {
    data class OpenCreateAgentDialog(
        val messageId: MessageId,
        val prefilledName: String,
        val prefilledDescription: String?,
        val draftHint: String?,
    ) : ThreadActionCardEffect

    data class OpenCreateChannelDialog(...) : ThreadActionCardEffect
    data class OpenAddMembersDialog(...) : ThreadActionCardEffect
}
```

The dialog layer then owns the regular create flow:

- `agent:create`: open or reuse the shared Create Agent dialog with editable name/description prefilled, require machine/runtime/model selection, submit through the existing typed `RaftAgentsApiClient.createAgent` body with `model`, `runtime`, `reasoningEffort`, `machineId`, `envVars`, `avatarUrl`, and `onboarding`.
- `channel:create`: open or reuse the shared Create Channel dialog with prefilled name/description/visibility/members, submit through `ChannelRepository.createChannel`.
- `channel:add_member`: open or reuse the shared Add Members dialog with preselected humans/agents, submit through `MemberRepository.addChannelMember`.

After the regular submit succeeds, the card flow must call `mark-executed` and use the response metadata if present. If the response does not include usable metadata, KMP may apply a local executed-card patch as an optimistic fallback, but it should also schedule or trigger a refresh so server state becomes authoritative.

## No-Op Diagnostic Matrix

When a user says "confirm has no reaction", diagnose the exact stage instead of treating it as one bug:

| Stage | Expected signal | Failure symptom | Required handling |
| --- | --- | --- | --- |
| UI enabled state | Button enabled for prepared supported cards; pending label appears after click | Button disabled, no pending change | Log card type/state/message id and why disabled |
| UI event | `commitActionCard` or `OpenActionCardDialog` effect emitted once | Tap does nothing | Add bridge/store log before async work |
| Dialog open | Dialog appears with prefilled, editable values | No modal | Route controller must surface effect errors; toast is not enough |
| Submit start | Dialog validates, disables submit, emits `execute_attempt` | Confirm closes or idles | Keep dialog open and show inline validation |
| Typed create API | Resource is created through Repository/API | Generic failure/no toast | Surface typed error code; do not call `mark-executed` |
| Mark executed | `POST /actions/{messageId}/mark-executed` succeeds | Resource exists but card stays prepared | Show non-blocking warning/log and refresh card metadata |
| Metadata patch | Store patches the same message id; realtime `message:updated` reconciles | Done state disappears after refresh | Treat server metadata as source of truth and test reducer patching |

Diagnostics should include low-cardinality transition logs:

- `thread_action_card.click`
- `thread_action_card.dialog_open`
- `thread_action_card.submit_start`
- `thread_action_card.create_success`
- `thread_action_card.create_fail`
- `thread_action_card.mark_executed_success`
- `thread_action_card.mark_executed_fail`
- `thread_action_card.metadata_patch`

Do not log tokens, bearer headers, env var values, or raw secret payloads.

## Error Semantics

- If the card is missing, unsupported, or already executed, keep the current immediate user feedback.
- If no required API/repository client exists, show an actionable error and log a distinct code such as `action_card_agents_api_missing`.
- If dialog validation fails, keep the dialog open and show an inline message.
- If create fails, keep the dialog open, emit/log `execute_fail`, and do not call `mark-executed`.
- If create succeeds but `mark-executed` fails, keep the resource, warn/log, and refresh the source message/card. Do not attempt to delete the created resource as rollback.
- If `mark-executed` returns an already-executed card, treat it as idempotent success and patch from returned metadata.

## Acceptance Criteria

- Tapping an `agent:create` card opens a Create Agent dialog instead of directly calling `POST /agents`.
- Create Agent name/description are prefilled from card metadata but editable.
- The user must choose a valid online machine/runtime/model before submit, matching React.
- Successful dialog submit creates the agent through typed `RaftAgentsApiClient` / repository boundary and then calls `mark-executed`.
- The card flips to Done using server-returned metadata and remains Done after refresh/realtime update.
- Create failure is visible in the dialog and does not mark the card executed.
- Mark-executed failure is visible enough for debugging and triggers a metadata refresh path.
- `channel:create` and `channel:add_member` use the same dialog-driven pattern or have an explicit documented exception; no hidden one-off direct UI path.
- UI, Store, Repository/API, and DataSource boundaries match the shared MVI foundation: UI emits intents/effects, typed APIs live below Store/Interactor, and server metadata returns through reducer patching.

## Suggested Tests

- `ThreadActionCardViewTest`: prepared `agent:create` click emits an open-dialog effect and sets pending/disabled state only around the effect, not around a hidden direct create call.
- `SharedThreadStoreTest`: `OpenCreateAgentDialog` effect carries message id, name, description, and draft hint; it does not call `agentsApi.createAgent`.
- `ThreadActionCardSubmitTest`: fake Create Agent dialog success calls `mark-executed` with `{kind:"agent", id, name}` and applies returned metadata to the same message id.
- Failure tests:
  - create-agent validation failure keeps dialog open and does not call `mark-executed`;
  - create API failure does not patch the card to Done;
  - mark-executed failure leaves the created agent result intact, emits/logs failure, and requests refresh.
- Contract test: `agent:create` and `channel:add_member` never call `/actions/{messageId}/execute`; `channel:create` behavior is either dialog-driven or covered by a deliberate one-click exception test.
- Compile/smoke: shared Android compile and a manual Android smoke opening a real action-card message in Thread.

## Rollout

1. Add a typed action-card dialog effect / route state in the Thread MVI layer.
2. Reuse existing create-agent, create-channel, and add-members dialog surfaces where possible. If any dialog is web-only today, add the smallest shared KMP dialog wrapper instead of embedding create logic inside `ThreadPage.kt`.
3. Move current direct `commitAgentCreateActionCardInternal` and `commitChannelAddMembersActionCard` behavior behind dialog submit handlers or remove them if no longer needed.
4. Parse/check `markActionExecuted` results and patch from returned metadata.
5. Add transition diagnostics and focused tests from the matrix above.
6. Re-run Android compile and one real-card Thread smoke.

## Non-Goals

- Do not auto-select machine/runtime/model for `agent:create`.
- Do not add technical runtime fields to `ActionCardAction.agent:create`.
- Do not mark an action card executed before the underlying resource is created.
- Do not let Thread UI call raw network APIs, tokens, or host bridges directly.
- Do not make Product/visual fixtures the source of truth for card state; server action metadata remains canonical.
