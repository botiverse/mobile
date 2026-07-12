/*
 * T-wrapper persistence — the V1 shadow-only mechanical upgrade gate (RFC 043 V1→V2 roadmap).
 *
 * V1 (`canonicalFactJson`) is wire-faithful lossless: the shadow-period parity/restore source. It carries
 * transitional producer-DTO debt (a full reaction roster in the node fact, not scale-safe at 5000×30), so it
 * is deliberately NOT sole-apply-eligible. This tripwire pins that flag to `false`: flipping it must be a
 * conscious edit that lands WITH the V2 normalized envelope (roster/children out of the node fact), routing
 * through review — so V1 can never be silently promoted to the sole-apply terminal contract.
 */
package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertFalse

class CanonicalFactSoleApplyEligibilityTest {

    @Test
    fun v1EnvelopeIsShadowOnlyNotSoleApplyEligible() {
        assertFalse(
            CANONICAL_FACT_SOLE_APPLY_ELIGIBLE,
            "V1 canonical fact envelope is wire-faithful shadow-only (full reaction roster = transitional " +
                "debt); it must not be sole-apply-eligible until the V2 normalized envelope lands.",
        )
    }
}
