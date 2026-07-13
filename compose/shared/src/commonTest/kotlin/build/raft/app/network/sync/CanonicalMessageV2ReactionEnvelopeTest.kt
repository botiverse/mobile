package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalMessageV2ReactionEnvelopeTest {
    private val valid =
        """{"schemaVersion":5,"kind":"normalized-message-v2","fact":{"reactions":[{"emoji":"fire","count":2,"previewK":[{"id":"actor-a","displayName":"Ada"},{"id":"actor-b","displayName":"Bee"}]},{"emoji":"like","count":0,"previewK":[]}]}}"""

    @Test
    fun acceptsOnlyExactCanonicalReactionEnvelope() {
        assertTrue(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid))
        assertFalse(CANONICAL_FACT_SOLE_APPLY_ELIGIBLE, "V1 shadow envelope must remain ineligible")
    }

    @Test
    fun singleFlagTruthTableKeepsShadowAndSoleApplyMechanicallySeparate() {
        assertEquals(
            NormalizedReactionRolloutMode.Off,
            normalizedReactionRolloutMode(
                syncCoreMessagesEnabled = false,
                envelopeSoleApplyEligible = true,
                globalSoleApplyEligible = true,
            ),
        )
        assertEquals(
            NormalizedReactionRolloutMode.ShadowOnly,
            normalizedReactionRolloutMode(
                syncCoreMessagesEnabled = true,
                envelopeSoleApplyEligible = true,
            ),
            "the production global eligibility switch is still false",
        )
        assertEquals(
            NormalizedReactionRolloutMode.ShadowOnly,
            normalizedReactionRolloutMode(
                syncCoreMessagesEnabled = true,
                envelopeSoleApplyEligible = false,
                globalSoleApplyEligible = true,
            ),
        )
        assertEquals(
            NormalizedReactionRolloutMode.SoleApply,
            normalizedReactionRolloutMode(
                syncCoreMessagesEnabled = true,
                envelopeSoleApplyEligible = true,
                globalSoleApplyEligible = true,
            ),
        )
    }

    @Test
    fun rejectsUnknownEnvelopeFactAndReactionKeysWithoutScanningUnrelatedMessagePayloads() {
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                valid.replace("\"fact\":", "\"legacyRaw\":{},\"fact\":"),
            ),
        )
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                valid.replace("\"reactions\":", "\"legacyRaw\":{},\"reactions\":"),
            ),
        )
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                valid.replace("\"previewK\":", "\"reactorIds\":[\"actor-a\"],\"previewK\":"),
            ),
        )
    }

    @Test
    fun rejectsInvalidBoundsDuplicatesAndNonCanonicalOrder() {
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                """{"schemaVersion":5,"kind":"normalized-message-v2","fact":{"reactions":[{"emoji":"fire","count":0,"previewK":[{"id":"actor-a","displayName":"Ada"}]}]}}""",
            ),
        )
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                """{"schemaVersion":5,"kind":"normalized-message-v2","fact":{"reactions":[{"emoji":"fire","count":2,"previewK":[{"id":"actor-b","displayName":"Bee"},{"id":"actor-a","displayName":"Ada"}]}]}}""",
            ),
        )
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                """{"schemaVersion":5,"kind":"normalized-message-v2","fact":{"reactions":[{"emoji":"like","count":0,"previewK":[]},{"emoji":"fire","count":0,"previewK":[]}]}}""",
            ),
        )
        assertFalse(
            isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
                """{"schemaVersion":5,"kind":"normalized-message-v2","fact":{"reactions":[{"emoji":"fire","count":0,"previewK":[]},{"emoji":"fire","count":0,"previewK":[]}]}}""",
            ),
        )
    }

    @Test
    fun rejectsStringsThatNormalizerWouldTrim() {
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid.replace("\"fire\"", "\" fire \"")))
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid.replace("\"actor-a\"", "\" actor-a \"")))
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid.replace("\"Ada\"", "\" Ada \"")))
    }

    @Test
    fun rejectsWrongVersionKindAndMalformedJson() {
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid.replace("\"schemaVersion\":5", "\"schemaVersion\":4")))
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid.replace("normalized-message-v2", "wire-message-v1")))
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(valid.replace("\"emoji\":\"fire\"", "\"emoji\":{}")))
        assertFalse(isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible("not-json"))
    }
}
