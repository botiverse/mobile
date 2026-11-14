package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The minimum build gate's pure decision ([decideRaftMinBuildGate]) and the parsers feeding it. */
class RaftMinBuildGateDecisionTest {
    private val url = "https://example.test/raft.apk"

    @Test
    fun thresholdIsAPlainPositiveBuildNumberAtLeastOnePointZero() {
        assertEquals(12_000_000L, raftMinBuildGateThreshold(flagEnabled = true, variant = "12000000"))
        assertEquals(12_601_003L, raftMinBuildGateThreshold(flagEnabled = true, variant = " 12601003 "))
        assertEquals(10_000_000L, raftMinBuildGateThreshold(flagEnabled = true, variant = "10000000"))
        listOf(null, "", " ", "1.20.0", "12,000,000", "-12000000", "+12000000", "0", "9999999", "12000000.0", "abc", "١٢٠٠٠٠٠٠", "9999999999999")
            .forEach { variant ->
                assertNull(raftMinBuildGateThreshold(flagEnabled = true, variant = variant), "variant=$variant")
            }
        // Flag off: no gate whatever the variant says.
        assertNull(raftMinBuildGateThreshold(flagEnabled = false, variant = "12000000"))
    }

    @Test
    fun noGateUnlessFlagThresholdAndCurrentBuildAreAllCertain() {
        val available = RaftInstallChannelLatest.Available(build = 12_600_000L, updateUrl = url)
        data class Case(val enabled: Boolean, val variant: String?, val current: Long?)
        listOf(
            Case(enabled = false, variant = "12000000", current = 11_900_000L), // flag off
            Case(enabled = true, variant = null, current = 11_900_000L), // no variant
            Case(enabled = true, variant = "1.20.0", current = 11_900_000L), // a version name, not a build
            Case(enabled = true, variant = "9999999", current = 1L), // below the 1.0.0 floor
            Case(enabled = true, variant = "12000000", current = null), // this build unknown
            Case(enabled = true, variant = "12000000", current = 1L), // a dev build ("1")
            Case(enabled = true, variant = "12000000", current = 12_000_000L), // at the threshold
            Case(enabled = true, variant = "12000000", current = 12_500_002L), // above it
        ).forEach { case ->
            assertEquals(
                RaftMinBuildGateDecision.None,
                decideRaftMinBuildGate(case.enabled, case.variant, case.current, available),
                "$case",
            )
        }
    }

    @Test
    fun blocksOnlyWhenTheChannelOffersABuildAtOrAboveTheThresholdWithSomewhereToGetIt() {
        assertEquals(
            RaftMinBuildGateDecision.Block(threshold = 12_000_000L, latestBuild = 12_600_000L, updateUrl = url),
            decideRaftMinBuildGate(true, "12000000", 11_900_003L, RaftInstallChannelLatest.Available(12_600_000L, url)),
        )
        assertEquals(
            RaftMinBuildGateDecision.Block(threshold = 12_000_000L, latestBuild = 12_000_000L, updateUrl = url),
            decideRaftMinBuildGate(true, "12000000", 11_900_003L, RaftInstallChannelLatest.Available(12_000_000L, url)),
        )
    }

    @Test
    fun belowTheThresholdWithoutConfirmationIsOnlyABanner() {
        val fallback = "https://store.example.test/raft"
        data class Case(val channel: RaftInstallChannelLatest, val expectedUrl: String?)
        listOf(
            // The store's latest is lower than the threshold: the threshold was set too high.
            Case(RaftInstallChannelLatest.Available(build = 11_950_000L, updateUrl = url), expectedUrl = url),
            // Offered, but nowhere to get it.
            Case(RaftInstallChannelLatest.Available(build = 12_600_000L, updateUrl = null), expectedUrl = fallback),
            Case(RaftInstallChannelLatest.Available(build = 12_600_000L, updateUrl = " "), expectedUrl = fallback),
            Case(RaftInstallChannelLatest.UpToDate, expectedUrl = fallback),
            Case(RaftInstallChannelLatest.Unknown, expectedUrl = fallback),
        ).forEach { case ->
            assertEquals(
                RaftMinBuildGateDecision.Banner(threshold = 12_000_000L, updateUrl = case.expectedUrl),
                decideRaftMinBuildGate(true, "12000000", 11_900_000L, case.channel, fallbackUpdateUrl = fallback),
                "${case.channel}",
            )
        }
        assertEquals(
            RaftMinBuildGateDecision.Banner(threshold = 12_000_000L, updateUrl = null),
            decideRaftMinBuildGate(true, "12000000", 11_900_000L, RaftInstallChannelLatest.Unknown),
        )
    }

    @Test
    fun eachOsReadsItsOwnKeyAndDesktopHasNone() {
        assertEquals(MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY, raftMinBuildGateFlagKey(FEATURE_FLAG_CLIENT_OS_ANDROID))
        assertEquals(MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY, raftMinBuildGateFlagKey(FEATURE_FLAG_CLIENT_OS_IOS))
        assertNull(raftMinBuildGateFlagKey("desktop"))
        assertEquals("mobile_min_build_android_v1", MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY)
        assertEquals("mobile_min_build_ios_v1", MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY)
        listOf(MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY, MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY).forEach { key ->
            assertEquals(true, key in REGISTERED_SERVER_FEATURE_FLAG_KEYS, key)
            assertEquals(false, key in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS, key)
        }
    }

    @Test
    fun storeVersionNamesReadAsTheLowestBuildOfThatRelease() {
        assertEquals(12_600_000L, raftBuildNumberOfVersionName("1.26.0"))
        assertEquals(12_601_000L, raftBuildNumberOfVersionName("1.26.1"))
        assertEquals(12_000_000L, raftBuildNumberOfVersionName("1.20"))
        listOf(null, "", "1", "1.26.0.1", "v1.26.0", "1.100.0", "1.26.100", "1.-1.0", "1..0").forEach { name ->
            assertNull(raftBuildNumberOfVersionName(name), "name=$name")
        }
    }

    @Test
    fun appStoreLookupGivesTheListingForThisBundle() {
        val body =
            """{"resultCount":1,"results":[{"bundleId":"build.raft.app","version":"1.26.0",""" +
                """"trackViewUrl":"https://apps.apple.com/us/app/raft/id123?uo=4","trackId":123}]}"""
        val listing = parseRaftAppStoreLookup(body, bundleId = "build.raft.app")
        assertEquals(RaftAppStoreListing(version = "1.26.0", trackViewUrl = "https://apps.apple.com/us/app/raft/id123?uo=4"), listing)
        assertNull(parseRaftAppStoreLookup(body, bundleId = "build.other.app"))
        assertNull(parseRaftAppStoreLookup("""{"resultCount":0,"results":[]}""", bundleId = "build.raft.app"))
        assertNull(parseRaftAppStoreLookup("<html>", bundleId = "build.raft.app"))

        assertEquals(
            RaftInstallChannelLatest.Available(build = 12_600_000L, updateUrl = listing?.trackViewUrl),
            raftAppStoreLatest(listing, currentBuild = 12_500_004L),
        )
        assertEquals(RaftInstallChannelLatest.UpToDate, raftAppStoreLatest(listing, currentBuild = 12_600_001L))
        assertEquals(RaftInstallChannelLatest.Unknown, raftAppStoreLatest(null, currentBuild = 12_500_004L))
        assertEquals(RaftInstallChannelLatest.Unknown, raftAppStoreLatest(listing, currentBuild = 1L))
    }

    @Test
    fun theEffectiveThresholdIsTheHighestServerReadingInAnyOrder() {
        val forward = linkedMapOf("server-a" to 12_000_000L, "server-b" to 12_200_000L, "server-c" to 12_100_000L)
        val backward = LinkedHashMap(forward.entries.reversed().associate { it.key to it.value })
        listOf(forward, backward).forEach { readings ->
            assertEquals(RaftMinBuildGateEffective(12_200_000L, "server-b"), raftMinBuildGateEffectiveThreshold(readings), "$readings")
        }
        // A tie names the smallest server id, so the log line does not depend on order either.
        val tie = mapOf("server-z" to 12_200_000L, "server-b" to 12_200_000L)
        assertEquals(RaftMinBuildGateEffective(12_200_000L, "server-b"), raftMinBuildGateEffectiveThreshold(tie))
        assertEquals(RaftMinBuildGateEffective(12_200_000L, "server-b"), raftMinBuildGateEffectiveThreshold(LinkedHashMap(tie.entries.reversed().associate { it.key to it.value })))
        assertNull(raftMinBuildGateEffectiveThreshold(emptyMap()))
    }
}
