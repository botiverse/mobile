# Search API Contract

Last verified from web client bundle and Android opt-in live test scaffold: 2026-05-15.

## Endpoint

`GET /api/messages/search`

The request must include normal auth headers and the selected server scope:

- `Authorization: Bearer <access token>`
- `X-Server-Id: <server id>`

Do not log or persist bearer tokens in tests, diagnostics, or docs.

## Query Parameters

| Param | Required | Notes |
| --- | --- | --- |
| `q` | yes | Search text. Android trims before sending. Blank search returns local empty state without network. |
| `limit` | yes | Page size. Web currently sends `20`. |
| `offset` | yes | Result offset for pagination. |
| `sort` | no | Omit for relevance/default. Send `recent` for newest-first. |
| `senderId` | no | Current-user id for "My messages" filtering, or another sender id if supported by product UI later. |
| `channelId` | no | Restricts results to one channel/DM id. |
| `after` | no | ISO-8601 timestamp lower bound, e.g. `2026-05-14T16:00:00.000Z`. |
| `before` | no | ISO-8601 timestamp upper bound. |

Web date ranges map to:

- `today`: `after` = local start of day converted to ISO, `before` = now.
- `7d`: `after` = now minus 7 days, `before` = now.
- `30d`: `after` = now minus 30 days, `before` = now.
- `any`: omit both bounds.

## Response Shape

The repository accepts result arrays under `items`, `results`, or `messages`, matching existing inbox-like mapping tolerance.
Known web usage reads:

- `results`: array of message hits.
- `hasMore`: boolean for pagination.

Android also reads:

- `totalCount`: optional result count; falls back to mapped item count.

## Regression Coverage

- `RealSlockRepositoriesTest.searchRepositoryForwardsWebSearchFilterParams` verifies Android forwards `sort`, `senderId`, `channelId`, `after`, and `before`.
- `LiveSlockDbFlowTest.liveSearchAcceptsWebFilterParams` is opt-in with `SLOCK_LIVE_TESTS=true`, `SLOCK_TEST_EMAIL`, and `SLOCK_TEST_PASSWORD`; it sends the web-style query to the live API and checks the stable response keys.
