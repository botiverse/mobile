# Activity row contract cases

The two rules an Activity row is kept by between inbox answers, written once
as cases that web, the app (Kotlin Multiplatform) and the server run. Task:
#triage:#244 (the inbox redesign: snapshot + ordered events). Same format
family as [`../unread/`](../unread/README.md); this directory has its own
schema.

- **`position`**: the per-row position rule. A served row says what it has
  counted up to (its `latestActivitySeq`, P). The client applies live
  messages above P, ignores one it already applied, and re-reads **that one
  row** (`GET /api/channels/inbox?ids=<row>`, never the list) when a message
  at or below P was not applied by it, when an answer arrives after a commit
  order inversion, or when the row has no position.
- **`predicate`**: does a live message count as unread on its row for this
  viewer, and does it light the @.

Shared implementation: [`reduceActivityRow`](../../src/activityRowPosition.ts)
and [`activityLiveUnread`](../../src/activityUnreadPredicate.ts) in
`@botiverse/raft-shared`. Nothing applies them yet (no behaviour change):
web adopts them in step 2, behind a flag.

Plain JSON, no dependencies: `case.schema.json` (JSON Schema 2020-12) and one
case per file in `cases/`.

## A case

```json
{
  "formatVersion": 1,
  "id": "reverse_commit_live_after_answer",
  "rule": "position",
  "title": "101 commits before 100; the answer at 101 misses 100, and live 100 arrives after it: re-read the row once",
  "status": "agreed",
  "given": { "row": { "unreadCount": 0, "hasMention": false, "position": "99", "mentionOnly": false } },
  "steps": [
    { "act": "answer", "row": { "unreadCount": 1, "hasMention": false, "position": "101", "mentionOnly": false } },
    { "act": "liveMessage", "seq": "100", "counts": true, "mentionsViewer": false },
    { "act": "answer", "row": { "unreadCount": 2, "hasMention": false, "position": "101", "mentionOnly": false } }
  ],
  "expect": { "row": { "unreadCount": 2, "position": "101" }, "reread": true }
}
```

| Field | Meaning |
| --- | --- |
| `formatVersion` | `1`. See [Versioning](#versioning). |
| `id` | The file name without `.json`, `snake_case`. Predicate cases start with `predicate_`. |
| `rule` | `position` or `predicate`. |
| `title`, `why` | What the case says, and why the rule is so. |
| `status` | `agreed`, or `pending-product-decision` (see below). |
| `sources` | Where web / app / server implement it today. Documentation only. |
| `knownGaps` | An agreed rule a side does not follow yet: `{client, reason, followUp}` (`client`: `web`, `app`, `server`). That side skips the case and reports the reason. |
| `given` | `position`: `row`, the row as its last answer delivered it (nothing applied yet). `predicate`: `viewer {type, id}`, `scope`, `message`. |
| `steps` | `position`: acts in order. `predicate`: always `[]`. |
| `checkpoints` | `position` only: `{afterStep (1-based), row?, reread?}`. |
| `expect` | `position`: `{row?, reread}`. `predicate`: `{verdict}`. |
| `decision` | Pending cases only: `{question, owner, candidates[]}`. |

### The row (`given.row`, an `answer`'s `row`)

`{unreadCount, hasMention, position, mentionOnly}`: the served row's unread
inputs. `position` is its `latestActivitySeq`, or `null` when the server could
not pair it with the row's latest message: such a row takes no live patch.

### Seqs are decimal strings

Every seq in a case (a row's `position`, a `liveMessage`'s or a `read`'s
`seq`, an expected `position`) is a canonical decimal string
(`^(0|[1-9][0-9]*)$`), as the server sends `latestActivitySeq`.
`messages.seq` is a 64-bit bigserial, and a floating-point number loses
precision past 2^53. Loaders keep seqs as strings or exact 64-bit integers
(BigInt, Kotlin `Long`) and compare them exactly, never as `Number`/`Double`.
`seq_above_2_53` (9007199254740993 vs 9007199254740992) fails a float
compare. The shared reducer takes and returns decimal strings and compares
them with `compareActivityRowSeq`. A
`mentionOnly` row (a mention with nothing else unread) has the highest
mention seq as its position.

### Acts (`position`)

`act` is the discriminator (kotlinx.serialization: `classDiscriminator = "act"`).

| Act | Fields | Meaning |
| --- | --- | --- |
| `liveMessage` | `seq`, `counts` (`true`/`false`/`null`), `mentionsViewer` | A live message of the row's conversation arrives, with the predicate's verdict for it. `counts: null` = it cannot be judged (re-read the row). |
| `answer` | `row` | An inbox answer with this row arrives (a list read or a single-row re-read). |
| `read` | `seq` | The viewer's read up to `seq` is applied (a persisted read, a read change's `refSeq`). |

The rule, in full (design doc "Fact 1"):

1. Live `seq > P` and not applied: apply it (count it if `counts`; a mention
   lights the @). An applied `seq` lower than one already applied since the
   last answer is an **inversion**.
2. Live `seq > P` already applied: ignore it (the same message again).
3. Live `seq <= P`: re-read the row (it may have committed after a higher seq).
   Since the applied set resets on every answer, a copy of a seq an answer
   already covered also re-reads (`duplicate_covered_by_answer_rereads`).
4. A row with `position: null`: every live message re-reads it.
5. An answer at `Pa < P`: keep the row. Otherwise take the answer and apply
   again the seqs applied above `Pa`; those at or below `Pa` are trusted as
   counted in it, unless an inversion was seen: then re-read the row.
6. A mention-only row: a counted message above it, a counted mention
   included, makes it a normal unread row; a mention that does not count (a
   muted conversation) lights the @ and keeps it mention-only.
7. A read at or past the shown position clears the unread and @. A read below
   it, on a mention-only row, or on a row without a position, re-reads the row.

### Predicate inputs

`scope`: `rowKind` (`channel`, `dm`, `thread`, `task`), `threadFollowed`
(thread/task rows; absent = true), `activityMuted` (channel/DM rows; a live
message is past the mute boundary), `readOnArrival` (the client reads it as it
arrives: open at the bottom with the read saved, a queued read, a read hold).

`message`: `senderType`, `senderId`, `messageType` (`chat`/`system`),
`causalActorType`, `causalActorId`, `systemSubtype` (`null` = none; **absent**
= the event did not carry the system provenance), `mentions [{type, id}]`.

### Expectations

- `row`: what the row shows; only the listed keys are asserted. `position` is
  the shown `latestActivitySeq`: the delivered one, or the highest seq applied
  above it.
- `reread`: some step since the case started asked to re-read this one row.
  It does not say which request (agreed with the app): a client asserts that
  it asked for a single-row re-read of the case's row, not the list.
- `verdict`: `{counts, mention}`. `counts: null` = cannot be judged from the
  event; the row is re-read.

## Pending product decisions

Where the sides differ and nobody has chosen yet, the case is
`"status": "pending-product-decision"` with no `expect`;
`decision.candidates` records each candidate's full expectation and who
behaves like it today (`currentBehaviourOf`: `web`, `app`, `server`). Loaders
skip it for the rule and report the question; web and the server each check
that their own candidate is still what they do. Once decided, move the chosen
expectation to the top level, set `"status": "agreed"`, record it below, and
add a `knownGaps` entry for a side that still differs. None is open today.

A web `knownGaps` entry here is about web's live patch today
(`inboxInPlaceUnread.ts`), not the shared rule: web's loader still runs the
case against the shared rule (which web adopts in step 2), and checks that its
live patch still differs, so the entry is removed when it stops differing.

## Decisions

| Date | Who | Where | Cases | Decision | Differs today |
| --- | --- | --- | --- | --- | --- |
| 2026-11-14 | Tenny | #14988 review | `predicate_channel_own_caused_system`, `predicate_dm_own_caused_system` | A system message the viewer caused does not count, on any row kind (RisingWave). | web live patch (thread rows only); canonical Postgres read |
| 2026-11-14 | Tenny | #14988 review | `predicate_channel_uncounted_subtype`, `predicate_channel_self_unfollow_subtype` | `channel.self_unfollow_thread` and `task.deleted_summary` do not count, on any row kind (RisingWave). | web live patch (thread rows only); canonical Postgres read |
| 2026-11-14 | Tenny | #14988 review | `predicate_muted_channel_message` | A new message in a muted conversation does not count (RisingWave muted prefix). | web live patch (no mute input) |
| 2026-11-14 | Tenny | #14988 review | `predicate_muted_channel_mention` | A mention in a muted conversation lights the @ and does not count (RisingWave). | web live patch; canonical Postgres read |

Web's gaps close in step 2 (web switches to the shared predicate). The
canonical Postgres inline read in `getInboxItems` (search, guest access,
forced reads) has no case runner yet; it is listed here, not as a gap.

## Adding a case

1. Copy the closest case in `cases/`, name the file `<id>.json`.
2. Fill `sources`; set `status`, and `knownGaps` for a side that does not
   follow an agreed rule yet.
3. Run the shared check (schema, ids, and every agreed case against the
   shared rule):
   `pnpm --filter @botiverse/raft-shared exec vitest run src/activityRowContractCases.test.ts`.
4. Run the web loader:
   `pnpm --filter @botiverse/raft-web exec vitest run tests/contractCases.activityRow.test.ts`.
5. A predicate case: run the server check:
   `pnpm --filter @botiverse/raft-server exec vitest run src/services/activityUnreadPredicate.contractCases.test.ts`.
6. The app picks the case up when its vendored copy is next synced.

A new act, `given` key or expectation key: add it to the schema and this
README in the same PR, keeping `formatVersion`.

## Versioning

As [`../unread/`](../unread/README.md#versioning): additive changes keep
`formatVersion` (1); a loader that meets an act or key it cannot drive skips
that case and reports it, never passes it and never fails the whole suite. A
change in meaning or shape bumps `formatVersion`.

## Loaders

A loader validates every file, runs the agreed cases it can drive, and
reports every other case as skipped with its reason. A case is never counted
as passed without its expectations being checked.

- **Shared**: [`packages/shared/src/activityRowContractCases.test.ts`](../../src/activityRowContractCases.test.ts):
  the schema (Ajv strict), every file, `id` = file name, checkpoints in range,
  the named cases present, and every agreed case through `reduceActivityRow` /
  `activityLiveUnread`.
- **Web**: [`packages/web/tests/contractCases.activityRow.test.ts`](../../../web/tests/contractCases.activityRow.test.ts):
  every agreed case against the shared rule through the package path; and
  each predicate case through web's live patch today (`liveActivityUnread` +
  `liveUnreadFields`): an agreed case must match, unless it has a web
  `knownGaps` entry, then it must still differ.
- **Server**: [`packages/server/src/services/activityUnreadPredicate.contractCases.test.ts`](../../../server/src/services/activityUnreadPredicate.contractCases.test.ts):
  each predicate case the server decides, against the RisingWave
  `unread_count` FILTER read from `infra/risingwave/sql/075-…` (and the muted
  boundary, and `078-…` for task rows) executed in PGlite, and against
  `isMessageUnreadEligibleForReceiver`.
- **App** (botiverse/mobile): a pinned copy under
  `compose/shared/src/commonTest/resources/contract-cases/activity-row/` with
  a `SOURCE_SHA` file, as for `unread/`; commonTest decodes the cases and
  drives the app's row reducer and predicate.
