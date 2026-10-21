package build.raft.app.data

/**
 * `GET /api/channels/unread?summary=1` bodies for fakes. Shape: botiverse/slock staging
 * 259f9fe0b549cfd589ac37bce75e2e1c5ffec6d1 `packages/server/src/routes/channels.ts` (`/unread`,
 * `{ channels, threadUnread }`) and `packages/server/src/services/channelService.ts`
 * (`ChannelUnreadSummaryEntry`, `ThreadUnreadByParent`).
 */
internal const val EMPTY_CHANNEL_UNREAD_SUMMARY_BODY: String = """{"channels":{},"threadUnread":{}}"""

/** A summary with plain channel/DM counts (no mention, no followed thread), read state absent. */
internal fun channelUnreadSummaryBody(counts: Map<String, Int>): String =
    counts.entries.joinToString(prefix = """{"channels":{""", postfix = """},"threadUnread":{}}""") { (id, count) ->
        """"$id":{"unreadCount":$count,"hasMention":false,"hasAnyMention":false,"readState":{"kind":"absent"}}"""
    }

/**
 * A canonical summary as the server builds it (`getUnreadSummary` + `summarizeThreadUnreadByParent`):
 * a joined channel with an unread mention, a DM, a non-joined public channel with `hasNew`, two followed
 * threads with unread replies under `channel-general` (one mentions the user), and the parent fold.
 */
internal val CANONICAL_CHANNEL_UNREAD_SUMMARY_BODY: String =
    """
    {
      "channels": {
        "channel-general": {
          "unreadCount": 3,
          "hasMention": true,
          "hasAnyMention": true,
          "readState": {
            "kind": "present",
            "readStateVersion": 7,
            "maxReadSeq": "41",
            "latestActivity": { "messageId": "msg-44", "seq": "44" }
          }
        },
        "dm-cindy": {
          "unreadCount": 1,
          "hasMention": false,
          "hasAnyMention": false,
          "readState": { "kind": "present", "readStateVersion": 2, "maxReadSeq": "9", "latestActivity": null }
        },
        "channel-news": {
          "unreadCount": 0,
          "hasMention": false,
          "hasAnyMention": false,
          "hasNew": true,
          "readState": { "kind": "absent" }
        },
        "thread-a": {
          "unreadCount": 2,
          "hasMention": true,
          "hasAnyMention": true,
          "readState": { "kind": "present", "readStateVersion": 4, "maxReadSeq": "5", "latestActivity": { "messageId": "msg-a7", "seq": "7" } },
          "parentChannelId": "channel-general"
        },
        "thread-b": {
          "unreadCount": 1,
          "hasMention": false,
          "hasAnyMention": false,
          "readState": { "kind": "absent" },
          "parentChannelId": "channel-general"
        },
        "thread-read": {
          "unreadCount": 0,
          "hasMention": false,
          "hasAnyMention": true,
          "readState": { "kind": "present", "readStateVersion": 3, "maxReadSeq": "12", "latestActivity": { "messageId": "msg-r12", "seq": "12" } }
        }
      },
      "threadUnread": {
        "channel-general": { "count": 2, "hasMention": true }
      }
    }
    """.trimIndent()
