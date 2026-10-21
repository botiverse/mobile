# Channel members paired visual cases

The production `ThreadMembersPageScreen` delegates its unchanged roster rendering
to `ThreadMembersPageContent`. Network ownership, mutations, Back handling and
confirmation remain in the production screen. The build-gated
`ChannelMembersVisualFixture.kt` supplies deterministic typed state to that same
renderer, not a duplicated layout or product data in route parameters.

Baseline: `ChannelMembers.tsx`, presentation=page, from botiverse/slock.
Follower baseline: `ThreadAgentFollowers.tsx`; Android uses the real
`ThreadAgentFollowersHeader`/popover via `ThreadFollowersVisualFixture.kt`.
Fixture contract: botiverse/slock `packages/visual-testing/shared/memberParityFixture.json`,
revision `member-parity-488-v1`: Grace online admin, Josh offline, Agent online on
Peer Server; humans Alex owner and Maya (Product Designer); candidate Quill.
Owner can add/remove local members; peer member removal is restricted.
Chinese, brutal theme, 390×844 logical-pixel viewport.

Cases: roster, search Grace, agents collapsed, add open, followers open.
The first four capture typed UI states; they do not independently prove taps,
keyboard behavior or networking. Follower capture taps its real header trigger.
Report all visible differences; do not tune tolerances to make them pass.

iOS, physical devices, long-roster scroll, removal confirmation and
remove/undo states are not covered by these five cases. Mahua owns this scoped
comparison; unsupported variants remain pending, excluded from pass counts.

Use hosted Visual Testing with these exact case ids:
`components.channel.members.parity.roster,components.channel.members.parity.search,components.channel.members.parity.collapsed,components.channel.members.parity.add,components.thread.followers.parity`.
Pin `slock_source_ref` to the companion web-fixture commit. All five are real
component fixture comparisons, not real-screen or end-to-end acceptance.
