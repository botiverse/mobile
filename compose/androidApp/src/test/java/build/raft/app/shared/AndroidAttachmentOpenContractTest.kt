package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentOpenContractTest {
    @Test
    fun attachmentAndPrincipalIdentitiesNeverAliasPrivateFiles() {
        val first = identity(principalKey = "a".repeat(64), attachmentId = "attachment-a")
        val second = identity(principalKey = "a".repeat(64), attachmentId = "attachment-b")
        val third = identity(principalKey = "b".repeat(64), attachmentId = "attachment-a")

        val firstName = androidAttachmentPrivateCacheFileName(first)
        val secondName = androidAttachmentPrivateCacheFileName(second)
        val thirdName = androidAttachmentPrivateCacheFileName(third)

        assertNotEquals(firstName, secondName)
        assertNotEquals(firstName, thirdName)
        assertNotEquals(secondName, thirdName)
        assertTrue(firstName.startsWith("${first.principalKey}-"))
        assertTrue(firstName.endsWith("-${first.storageKey}"))
    }

    @Test
    fun attachmentIdsWithTheSameLegacySanitizedPrefixNeverAlias() {
        val sharedPrefix = "attachment" + "a".repeat(80)
        val first = identity(attachmentId = "$sharedPrefix/one")
        val second = identity(attachmentId = "$sharedPrefix?two")

        val firstName = androidAttachmentPrivateCacheFileName(first)
        val secondName = androidAttachmentPrivateCacheFileName(second)

        assertNotEquals(first.storageKey, second.storageKey)
        assertNotEquals(firstName, secondName)
        assertTrue(firstName.endsWith("-${first.storageKey}"))
        assertTrue(secondName.endsWith("-${second.storageKey}"))
        assertFalse(firstName.contains(first.attachmentId))
        assertFalse(secondName.contains(second.attachmentId))
    }

    @Test
    fun sameCanonicalIdentityDerivesTheSamePrivateName() {
        val beforeDetach = identity(principalKey = "c".repeat(64), attachmentId = "stable-attachment")
        val afterReopen = identity(principalKey = "c".repeat(64), attachmentId = "stable-attachment")

        assertEquals(
            androidAttachmentPrivateCacheFileName(beforeDetach),
            androidAttachmentPrivateCacheFileName(afterReopen)
        )
    }

    @Test
    fun identityOnlyNameLeavesRoomForAtomicDownloadPartSuffix() {
        val finalName = androidAttachmentPrivateCacheFileName(identity())
        val longestExpectedPartName = "$finalName.part-${"f".repeat(40)}"

        assertTrue(finalName.toByteArray(Charsets.UTF_8).size < 200)
        assertTrue(longestExpectedPartName.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun viewAndPackageSpecsCarryChooserAndOneShotReadGrantSemantics() {
        val viewSource = source(AndroidAttachmentOpenSourceKind.PublicDownload, "content://downloads/note")
        val viewSpec = androidAttachmentOpenLaunchSpec(viewSource, packageArchive = false)

        assertEquals(AndroidAttachmentOpenIntentAction.View, viewSpec.action)
        assertEquals("android.intent.action.VIEW", viewSpec.action.intentAction)
        assertTrue(viewSpec.launchInChooser)
        assertTrue(viewSpec.grantsReadPermission)
        assertEquals(AndroidAttachmentReadGrantScope.SelectedTarget, viewSpec.readGrantScope)
        assertTrue(viewSpec.placesContentUriInClipData)
        assertTrue(viewSpec.source.durableCache)

        val packageSource =
            source(
                kind = AndroidAttachmentOpenSourceKind.PrivateCache,
                uri = "content://private/app-apk",
                displayName = "app.apk",
                mimeType = "application/vnd.android.package-archive"
            )
        val packageSpec = androidAttachmentOpenLaunchSpec(packageSource, packageArchive = true)

        assertEquals(AndroidAttachmentOpenIntentAction.InstallPackage, packageSpec.action)
        assertEquals("android.intent.action.INSTALL_PACKAGE", packageSpec.action.intentAction)
        assertFalse(packageSpec.launchInChooser)
        assertTrue(packageSpec.grantsReadPermission)
        assertEquals(AndroidAttachmentReadGrantScope.SelectedTarget, packageSpec.readGrantScope)
        assertTrue(packageSpec.placesContentUriInClipData)
        assertFalse(packageSpec.source.durableCache)
    }

    @Test
    fun shareSpecSendsEvenAPackageThroughTheChooserWithAOneShotGrant() {
        val packageSource =
            source(
                kind = AndroidAttachmentOpenSourceKind.PublicDownload,
                uri = "content://downloads/app-apk",
                displayName = "app.apk",
                mimeType = "application/vnd.android.package-archive"
            )
        val shareSpec = androidAttachmentOpenLaunchSpec(packageSource, packageArchive = false, share = true)

        assertEquals(AndroidAttachmentOpenIntentAction.Send, shareSpec.action)
        assertEquals("android.intent.action.SEND", shareSpec.action.intentAction)
        assertTrue(shareSpec.launchInChooser)
        assertTrue(shareSpec.grantsReadPermission)
        assertEquals(AndroidAttachmentReadGrantScope.SelectedTarget, shareSpec.readGrantScope)
        assertTrue(shareSpec.placesContentUriInClipData)
    }

    @Test
    fun launchSourceRejectsRawFilePaths() {
        val result =
            runCatching {
                AndroidAttachmentContentUriSource(
                    kind = AndroidAttachmentOpenSourceKind.PrivateCache,
                    contentUri = "file:///data/user/0/build.raft.app/cache/report.txt",
                    mimeType = "text/plain",
                    displayName = "report.txt",
                    sizeBytes = 25L,
                    durableCache = false
                )
            }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun attachmentIdentityRejectsNonHexOrTraversalPrincipalKeys() {
        listOf(
            "A".repeat(64),
            "../" + "a".repeat(61),
            "g".repeat(64)
        ).forEach { principalKey ->
            val result =
                runCatching {
                    AndroidAttachmentPublicIdentity(
                        principalKey = principalKey,
                        attachmentId = "attachment"
                    )
                }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun identity(
        principalKey: String = "a".repeat(64),
        attachmentId: String = "attachment-1"
    ): AndroidAttachmentPublicIdentity =
        AndroidAttachmentPublicIdentity(
            principalKey = principalKey,
            attachmentId = attachmentId
        )

    private fun source(
        kind: AndroidAttachmentOpenSourceKind,
        uri: String,
        displayName: String = "report.txt",
        mimeType: String = "text/plain"
    ): AndroidAttachmentContentUriSource =
        AndroidAttachmentContentUriSource(
            kind = kind,
            contentUri = uri,
            mimeType = mimeType,
            displayName = displayName,
            sizeBytes = 25L,
            durableCache = kind == AndroidAttachmentOpenSourceKind.PublicDownload
        )

    @Test
    fun privateCacheHitWithoutPublicCommitReportsCachedFalse() {
        // task #319: a private staging copy must never flip `cached` to true — `cached`
        // is strictly "public download committed" and drives the Download-vs-Open-with UI.
        val payload =
            attachmentCacheStatusPayload(
                committed = null,
                privateCached = true,
                privatePath = "/cache/private/attachment-1"
            )

        assertEquals("ok", payload["status"])
        assertEquals(false, payload["cached"])
        assertEquals(true, payload["privateCached"])
        assertEquals("/cache/private/attachment-1", payload["privatePath"])
        assertEquals(-1L, payload["recordVersion"])
        assertEquals(0L, payload["sizeBytes"])
        assertEquals("", payload["uri"])
    }

    @Test
    fun privateCacheHitWithPublicCommitReportsCachedTrueAndPrivatePath() {
        val committed =
            AndroidAttachmentPublicRecord(
                identityKey = "principal:attachment-1",
                generation = 7L,
                phase = AndroidAttachmentPublicRecordPhase.Committed,
                uri = "content://media/downloads/42",
                displayName = "report.txt",
                mimeType = "text/plain",
                sizeBytes = 1234L
            )
        val payload =
            attachmentCacheStatusPayload(
                committed = committed,
                privateCached = true,
                privatePath = "/cache/private/attachment-1"
            )

        assertEquals(true, payload["cached"])
        assertEquals(true, payload["privateCached"])
        assertEquals("/cache/private/attachment-1", payload["privatePath"])
        assertEquals(7L, payload["recordVersion"])
        assertEquals(1234L, payload["sizeBytes"])
        assertEquals("content://media/downloads/42", payload["uri"])
    }
}
