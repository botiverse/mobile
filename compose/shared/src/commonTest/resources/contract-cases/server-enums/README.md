# Server enum values (pinned)

`server-enums.json` lists, per domain, every value botiverse/slock can send for an enum-like
field the app maps: channel types, Activity row kinds and channel types, message sender types and
message types, task statuses, notification surface kinds, runtime provider kinds (per runtime),
runtime auth runtimes, sidebar pinned kinds, agent activity states, object kinds and socket event
names. Each domain records the slock file and line it was read from. `SOURCE_SHA` is the slock
commit.

`ServerEnumContractTest` (`compose/shared/src/androidUnitTest/.../contract/`) checks that every
value maps to a typed app value, or to an explicit, logged fallback (a generic row or notice plus
a `projection_value_unknown … action=fallback_*` diagnostic). A silent drop fails the test.

## Refresh

```bash
export SLOCK_SOURCE_REPO=/path/to/botiverse/slock
git -C "$SLOCK_SOURCE_REPO" fetch origin
python3 scripts/refresh_server_enum_fixture.py --ref origin/staging          # rewrite both files
python3 scripts/refresh_server_enum_fixture.py --ref origin/staging --check  # exit 1 when values drifted
```

Then run `:shared:testDebugUnitTest --tests '*ServerEnumContractTest*'` (or let CI run it).

- If a new value fails the test, map it in the app (or give it a logged fallback). For socket
  events only, the test may instead record why mobile does not subscribe
  (`SocketEventsNotSubscribed`).
- If the script stops with "anchor not found", slock moved a symbol: update that domain's entry
  in `DOMAINS` in the script and run it again.
- A new domain goes in the script's `DOMAINS` and gets a check in the test;
  `everyFixtureDomainIsChecked` fails until both sides agree.
- Socket event names are a lower bound: events emitted through a variable that is not a
  `*_EVENT` constant are not found.
