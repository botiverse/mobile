# Live DB Flow Test

`LiveSlockDbFlowTest` is an opt-in integration test for the real Slock API. It is skipped by default and must not be enabled in normal CI.

The test covers:

- email/password login and in-memory session storage
- temporary channel creation
- parent message send in that channel
- reply thread create/open from `parentChannelId + parentMessageId`
- reply message send into the returned `threadChannelId`
- thread load and local SQLDelight write-through
- local DB assertions for parent channel, thread stream channel, message channel, and cursor scope

## Run Locally

Set credentials in your shell or in a local ignored `.env.local` file:

```bash
SLOCK_TEST_EMAIL=...
SLOCK_TEST_PASSWORD=...
SLOCK_LIVE_TESTS=true
```

Optional:

```bash
SLOCK_BASE_URL=https://api.slock.ai/api/
```

Then run:

```bash
scripts/run_live_slock_db_flow.sh
```

The script sources `.env.local` when present. Do not commit real credentials.
