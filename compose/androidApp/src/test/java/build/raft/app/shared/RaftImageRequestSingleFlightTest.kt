package build.raft.app.shared

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftImageRequestSingleFlightTest {
    @Test
    fun threeCallersShareOneFetchAndDecode() {
        val singleFlight = RaftImageRequestSingleFlight<RaftNetworkImageRequestKey, String>()
        val key = requestKey()
        var fetchCount = 0
        var decodeCount = 0
        lateinit var complete: (Result<String>) -> Unit
        val results = mutableListOf<String>()

        repeat(3) {
            singleFlight.load(
                key = key,
                start = { finish ->
                    fetchCount += 1
                    complete = { result ->
                        decodeCount += 1
                        finish(result)
                    }
                },
                callback = { result -> results += result.getOrThrow() }
            )
        }

        assertEquals(1, fetchCount)
        assertEquals(0, decodeCount)
        complete(Result.success("avatar"))
        assertEquals(1, decodeCount)
        assertEquals(listOf("avatar", "avatar", "avatar"), results)
        assertEquals(0, singleFlight.activeFlightCount())
    }

    @Test
    fun detachedCallerDoesNotCancelSharedFlightForRemainingCallers() {
        val singleFlight = RaftImageRequestSingleFlight<String, String>()
        lateinit var complete: (Result<String>) -> Unit
        val firstResults = mutableListOf<String>()
        val secondResults = mutableListOf<String>()
        val thirdResults = mutableListOf<String>()

        val first =
            singleFlight.load(
                key = "avatar",
                start = { complete = it },
                callback = { firstResults += it.getOrThrow() }
            )
        singleFlight.load(
            key = "avatar",
            start = { error("joined caller must not start a second flight") },
            callback = { secondResults += it.getOrThrow() }
        )
        singleFlight.load(
            key = "avatar",
            start = { error("joined caller must not start a second flight") },
            callback = { thirdResults += it.getOrThrow() }
        )

        first.detach()
        complete(Result.success("shared"))

        assertTrue(firstResults.isEmpty())
        assertEquals(listOf("shared"), secondResults)
        assertEquals(listOf("shared"), thirdResults)
        assertEquals(0, singleFlight.activeFlightCount())
    }

    @Test
    fun sharedFailureFansOutOnceAndAllowsRetry() {
        val singleFlight = RaftImageRequestSingleFlight<String, String>()
        val completions = mutableListOf<(Result<String>) -> Unit>()
        val failures = mutableListOf<Throwable>()
        var startCount = 0

        repeat(3) {
            singleFlight.load(
                key = "avatar",
                start = { finish ->
                    startCount += 1
                    completions += finish
                },
                callback = { result -> failures += result.exceptionOrNull() ?: error("expected failure") }
            )
        }
        val failure = IllegalStateException("offline")
        completions.single()(Result.failure(failure))

        assertEquals(1, startCount)
        assertEquals(3, failures.size)
        failures.forEach { observed -> assertSame(failure, observed) }
        assertEquals(0, singleFlight.activeFlightCount())

        val retryResults = mutableListOf<String>()
        singleFlight.load(
            key = "avatar",
            start = { finish ->
                startCount += 1
                completions += finish
            },
            callback = { retryResults += it.getOrThrow() }
        )
        assertEquals(2, startCount)
        completions.last()(Result.success("retry"))
        assertEquals(listOf("retry"), retryResults)
    }

    @Test
    fun lateCompletionFromOldFlightCannotCompleteNewRetry() {
        val singleFlight = RaftImageRequestSingleFlight<String, String>()
        val completions = mutableListOf<(Result<String>) -> Unit>()
        val firstResults = mutableListOf<Result<String>>()
        val retryResults = mutableListOf<String>()

        singleFlight.load(
            key = "avatar",
            start = { completions += it },
            callback = { firstResults += it }
        )
        completions[0](Result.failure(IllegalStateException("first failed")))
        singleFlight.load(
            key = "avatar",
            start = { completions += it },
            callback = { retryResults += it.getOrThrow() }
        )

        completions[0](Result.success("late old result"))
        assertTrue(retryResults.isEmpty())
        assertEquals(1, singleFlight.activeFlightCount())

        completions[1](Result.success("fresh retry"))
        assertEquals(listOf("fresh retry"), retryResults)
        assertEquals(0, singleFlight.activeFlightCount())
        assertTrue(firstResults.single().isFailure)
    }

    @Test
    fun requestKeyOnlyNormalizesGravatarSizeParameters() {
        val first = requestKey(url = "https://www.gravatar.com/avatar/hash?s=121")
        val changedServerSize = requestKey(url = "https://www.gravatar.com/avatar/hash?size=256")
        val changedDefault = requestKey(url = "https://www.gravatar.com/avatar/hash?s=121&d=404")
        val otherDefault = requestKey(url = "https://www.gravatar.com/avatar/hash?s=256&d=retro")
        val changedRating = requestKey(url = "https://www.gravatar.com/avatar/hash?r=g")
        val otherRating = requestKey(url = "https://www.gravatar.com/avatar/hash?r=x")
        val changedForceDefault = requestKey(url = "https://www.gravatar.com/avatar/hash?f=y")
        val noForceDefault = requestKey(url = "https://www.gravatar.com/avatar/hash")
        val differentSize = requestKey(requestWidth = 242, requestHeight = 242)
        val differentResize = requestKey(needResize = false)
        val differentTransform = requestKey(scaleType = "FIT_CENTER")

        assertEquals(first, changedServerSize)
        assertFalse(changedDefault == otherDefault)
        assertFalse(changedRating == otherRating)
        assertFalse(changedForceDefault == noForceDefault)
        assertFalse(first == differentSize)
        assertFalse(first == differentResize)
        assertFalse(first == differentTransform)
    }

    @Test
    fun requestKeyPreservesCdnContentAuthorizationAndVersionIdentity() {
        val tokenA = requestKey(url = "https://cdn.example.com/avatar/user.png?token=A")
        val tokenB = requestKey(url = "https://cdn.example.com/avatar/user.png?token=B")
        val signatureA = requestKey(url = "https://cdn.example.com/profile-images/user.png?signature=A&expires=1")
        val signatureB = requestKey(url = "https://cdn.example.com/profile-images/user.png?signature=B&expires=2")
        val versionOne = requestKey(url = "https://cdn.example.com/image.png?version=1")
        val versionTwo = requestKey(url = "https://cdn.example.com/image.png?version=2")
        val fragmentOne = requestKey(url = "https://cdn.example.com/image.png#one")
        val fragmentTwo = requestKey(url = "https://cdn.example.com/image.png#two")
        val lookalikeHostA = requestKey(url = "https://evilgravatar.com/avatar/hash?s=121")
        val lookalikeHostB = requestKey(url = "https://evilgravatar.com/avatar/hash?s=256")

        assertFalse(tokenA == tokenB)
        assertFalse(signatureA == signatureB)
        assertFalse(versionOne == versionTwo)
        assertFalse(fragmentOne == fragmentTwo)
        assertFalse(lookalikeHostA == lookalikeHostB)
    }

    @Test
    fun gravatarNormalizationPreservesRawQueryOrderDuplicatesEmptyValuesAndEncoding() {
        val identity =
            "https://www.gravatar.com/avatar/hash?d=404&x=&x=one%20two&s=121&x=two&&r=g#preview"
                .raftNetworkImageUrlIdentity()

        assertEquals(
            "https://www.gravatar.com/avatar/hash?d=404&x=&x=one%20two&x=two&&r=g#preview",
            identity
        )
    }

    @Test
    fun differentDecodeIdentityStartsIndependentFlights() {
        val singleFlight = RaftImageRequestSingleFlight<RaftNetworkImageRequestKey, String>()
        val completions = mutableListOf<(Result<String>) -> Unit>()

        singleFlight.load(
            key = requestKey(requestWidth = 121, requestHeight = 121),
            start = { completions += it },
            callback = {}
        )
        singleFlight.load(
            key = requestKey(requestWidth = 242, requestHeight = 242),
            start = { completions += it },
            callback = {}
        )

        assertEquals(2, completions.size)
        assertEquals(2, singleFlight.activeFlightCount())
        completions.forEach { it(Result.success("done")) }
        assertEquals(0, singleFlight.activeFlightCount())
    }

    @Test
    fun urlByteSingleFlightDeduplicatesAcrossDifferentDecodeDimensions() {
        val byteFlights = RaftImageRequestSingleFlight<String, ByteArray>()
        val url = "https://cdn.example.com/image.png"
        var byteFetchCount = 0
        lateinit var completeBytes: (Result<ByteArray>) -> Unit

        val results = mutableListOf<ByteArray>()

        // Simulate 2 decode requests with different dimensions sharing the byte flight
        val key1 = requestKey(url = url, requestWidth = -1, requestHeight = -1)
        val key2 = requestKey(url = url, requestWidth = 606, requestHeight = 1151)

        assertEquals(key1.urlIdentity, key2.urlIdentity)

        byteFlights.load(
            key = key1.urlIdentity,
            start = { finish ->
                byteFetchCount++
                completeBytes = finish
            },
            callback = { results += it.getOrThrow() }
        )

        byteFlights.load(
            key = key2.urlIdentity,
            start = { error("second decode request must join in-flight byte download") },
            callback = { results += it.getOrThrow() }
        )

        assertEquals(1, byteFetchCount)
        val fakeBytes = byteArrayOf(1, 2, 3)
        completeBytes(Result.success(fakeBytes))

        assertEquals(2, results.size)
        assertSame(fakeBytes, results[0])
        assertSame(fakeBytes, results[1])
        assertEquals(0, byteFlights.activeFlightCount())
    }

    @Test
    fun nestedAsyncFanoutDoesNotStarveFixedThreadPool() {
        val executor = Executors.newFixedThreadPool(4)
        val byteFlights = RaftImageRequestSingleFlight<String, ByteArray>()
        val imageFlights = RaftImageRequestSingleFlight<RaftNetworkImageRequestKey, String>()
        val url = "https://cdn.example.com/avatar.png"
        val count = 8
        val completedResults = CopyOnWriteArrayList<String>()
        val allDoneLatch = CountDownLatch(count)

        for (i in 0 until count) {
            val key = requestKey(url = url, requestWidth = 100 + i, requestHeight = 100 + i)
            imageFlights.load(
                key = key,
                start = { completeImage ->
                    byteFlights.load(
                        key = key.urlIdentity,
                        start = { completeBytes ->
                            Thread {
                                Thread.sleep(20)
                                completeBytes(Result.success(byteArrayOf(1, 2, 3)))
                            }.start()
                        },
                        callback = { byteResult ->
                            executor.execute {
                                val bytes = byteResult.getOrThrow()
                                val decoded = "bitmap-${bytes.size}-${key.requestWidth}"
                                completeImage(Result.success(decoded))
                            }
                        }
                    )
                },
                callback = { result ->
                    completedResults += result.getOrThrow()
                    allDoneLatch.countDown()
                }
            )
        }

        assertTrue("All requests must complete within 3 seconds without deadlock", allDoneLatch.await(3, TimeUnit.SECONDS))
        assertEquals(count, completedResults.size)
        executor.shutdown()
    }

    private fun requestKey(
        url: String = "https://www.gravatar.com/avatar/hash?s=121&d=404",
        requestWidth: Int = 121,
        requestHeight: Int = 121,
        needResize: Boolean = true,
        scaleType: String = "CENTER_CROP"
    ): RaftNetworkImageRequestKey =
        RaftNetworkImageRequestKey.create(
            url = url,
            requestWidth = requestWidth,
            requestHeight = requestHeight,
            needResize = needResize,
            scaleType = scaleType
        )
}
