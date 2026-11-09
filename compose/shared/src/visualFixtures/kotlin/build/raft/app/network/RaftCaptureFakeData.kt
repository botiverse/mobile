package build.raft.app.network

import build.raft.app.data.HomeChannelWireDto
import build.raft.app.data.HomeDirectMessageWireDto

/** PROTOTYPE capture: the fabricated workspace each fake server answers with. */
internal class RaftCaptureFakeData(
    val channels: List<HomeChannelWireDto>,
    val directMessages: List<HomeDirectMessageWireDto>,
) {
    companion object {
        /** A channel name long enough to test the bar's title truncation. */
        const val LongChannelName = "visual-parity-and-release-readiness-for-every-platform-and-locale"

        private fun channel(id: String, name: String, description: String?, minutesAgo: Int, type: String = "channel") =
            HomeChannelWireDto(
                id = id,
                name = name,
                type = type,
                description = description,
                lastMessageAt = minutesAgoIso(minutesAgo),
            )

        private fun dm(id: String, peerId: String, peer: String, peerType: String, preview: String, minutesAgo: Int) =
            HomeDirectMessageWireDto(
                id = id,
                name = peer,
                type = "dm",
                lastMessageAt = minutesAgoIso(minutesAgo),
                peerType = peerType,
                peerId = peerId,
                peerName = peer.lowercase(),
                peerDisplayName = peer,
                lastMessagePreview = preview,
                lastMessageSenderName = peer,
            )

        /** Fixed "now" for the capture: 2026-10-31T09:41:00Z. */
        fun minutesAgoIso(minutes: Int): String {
            val total = 9 * 60 + 41 - minutes
            val h = (total / 60).coerceAtLeast(0)
            val m = (total % 60 + 60) % 60
            return "2026-10-31T${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:00.000Z"
        }

        private val design =
            RaftCaptureFakeData(
                channels =
                    listOf(
                        channel("ch_capture_design", "design", "Design reviews and UI polish", 3),
                        channel("ch_capture_release", "release", "Release trains and changelogs", 12),
                        channel("ch_capture_mobile", "mobile", "iOS and Android", 25),
                        channel("ch_capture_long", LongChannelName, "A very long channel name for title truncation", 40),
                        channel("ch_capture_announcements", "announcements", "Team-wide news", 90),
                        channel("ch_capture_roadmap", "roadmap", "What ships next", 300, type = "private"),
                    ),
                directMessages =
                    listOf(
                        dm("dm_capture_cindy", "agt_capture_cindy", "Cindy", "agent", "Captured the native bar on iOS 27", 5),
                        dm("dm_capture_builder", "agt_capture_builder", "Builder", "agent", "Build 1.18.0 (412) is green", 30),
                        dm("dm_capture_maya", "usr_capture_maya", "Maya", "human", "Looks good to me", 120),
                    ),
            )

        private val bots =
            RaftCaptureFakeData(
                channels =
                    listOf(
                        channel("ch_capture_general", "general", "Botiverse general", 8),
                        channel("ch_capture_agents", "agents", "Agent runtime and computers", 45),
                    ),
                directMessages =
                    listOf(dm("dm_capture_josh", "usr_capture_josh", "Josh", "human", "Ping me when the build is out", 60)),
            )

        fun forServer(serverId: String): RaftCaptureFakeData =
            if (serverId == RaftCaptureFakeServer.BotsServerId) bots else design
    }
}
