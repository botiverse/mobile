# Mobile server switching: why only the current server holds a realtime socket

**Area:** realtime / multi-server
**Owner:** CC-Cata
**Verified at:** 2026-10-20
**Status:** current

## The observation

Switching servers on mobile is not instant: the list rebuilds and the realtime
connection re-establishes. The web client, by contrast, shows every joined server
side by side, so switching there is a pane change with no reconnect.

The instinct is to read this as an unfinished feature. It is not — the mobile client
deliberately narrowed from one socket per authenticated server to one socket for the
current server, and the reason is written down in the code.

## Where the decision lives

`compose/shared/src/commonMain/kotlin/build/raft/app/data/realtime/RealtimeProcessDesiredSession.kt`

The KDoc above `realtimeCurrentServerScopedServerIds` records both the change and its
rationale:

```
Scope the realtime transport set to the CURRENT server only, instead of
holding a persistent socket for every authenticated server (PR #1180). We
stay authenticated to every granted server (grants are unchanged), but keep
a live realtime connection for the selected server alone; non-current server
drawer unread rides the home snapshot's serverUnreadSummary, not a socket.
...
  - current server present AND among the grants -> exactly {current};
  - current unknown/blank, or not among the grants -> empty, so we keep zero
    persistent sockets until a granted current server is known (a later
    reconcile re-run opens it). Never fan out to every authenticated server.
```

The function itself returns `setOf(current)` or `emptySet()` — it cannot fan out.

The recorded rationale is a scale concern: holding a socket per authenticated server
does not bound the connection count when a user belongs to many servers.

## The result is three independent layers, not one

| Layer | Behaviour |
|---|---|
| Authentication | **All** granted servers stay authenticated (`grantsByServer` is complete). Switching back does not require signing in again. |
| Realtime connection | **One** socket — the current server only. |
| Non-current unread | Comes from `serverUnreadSummary` on the home snapshot, **not** a socket. |

So the cost of switching is connection re-establishment, not authentication. That is
the trade the narrowing made: bounded sockets, in exchange for a reconnect on switch.

## What the UI already knows

Two different granularities exist today, which is easy to miss:

- `homeServerSwitcherHasOtherUnread(currentServerId, servers)` — a **Boolean**. Answers
  *"does any other server have unread?"*, not *"which one, how many?"*.
- `homeServerSwitcherBadgePresentation(server)` — **per server**, returning a
  `HomeServerSwitcherBadgePresentation` whose `kind` is `None` / `ActivityNumber` /
  `BroaderUnreadNumber` and whose `label` carries the formatted count.

The per-server projection and its rendering already exist; the header currently
surfaces the Boolean. Anyone reasoning about "how much work to show other servers'
unread" should start from these two functions rather than from the data layer, because
the data layer is not the gap.

## Related

- Server switching itself has two entry points: the Home header popover, and the
  navigation rail header, which expands the switcher **in place** rather than
  borrowing Home's popover.
- Server ordering is user-adjustable (`ReorderServers`, drag handle).

## Scope of this article

This documents **shipped behaviour and recorded rationale**. It deliberately does not
argue for or against changing it, and it does not describe unshipped work.
