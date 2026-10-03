package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTermsAgreementTextTest {
    @Test
    fun chineseAgreementIsOneContinuousTextWithBothLinkRanges() {
        val text =
            authTermsAgreementText(
                prefix = "我同意",
                terms = "服务条款",
                connector = "并",
                acknowledgement = "确认",
                privacy = "隐私政策",
                suffix = "。",
            )

        assertEquals("我同意服务条款并确认隐私政策。", text.text)
        assertEquals(listOf("服务条款", "隐私政策"), text.spanStyles.map { text.text.substring(it.start, it.end) })
    }
    @Test
    fun policyLinksOpenTheirOwnDocument() {
        var termsOpened = 0
        var privacyOpened = 0
        val text = authTermsAgreementText(
            prefix = "I agree to ",
            terms = "Terms",
            connector = " and ",
            acknowledgement = "acknowledge ",
            privacy = "Privacy",
            suffix = ".",
            onOpenTerms = { termsOpened++ },
            onOpenPrivacy = { privacyOpened++ },
        )
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("Terms", "Privacy"), links.map { text.text.substring(it.start, it.end) })
        links[0].item.linkInteractionListener?.onClick(links[0].item)
        assertEquals(1, termsOpened)
        assertEquals(0, privacyOpened)
        links[1].item.linkInteractionListener?.onClick(links[1].item)
        assertEquals(1, termsOpened)
        assertEquals(1, privacyOpened)
    }
    @Test
    fun resourceConnectorKeepsSpacingButAllowsWordBreaks() {
        assertEquals(" and ", authLegalConnectorText("\u00A0and\u00A0"))
        assertEquals("并", authLegalConnectorText("并"))
        assertEquals("に同意し、", authLegalConnectorText("に同意し、"))
        val text = authTermsAgreementText(
            prefix = "I agree to the\u00A0",
            terms = "Terms of Service",
            connector = "\u00A0and\u00A0",
            acknowledgement = "acknowledge the\u00A0",
            privacy = "Privacy Policy",
            suffix = ".",
        )
        assertEquals(
            "I agree to the\u00A0Terms of Service and acknowledge the\u00A0Privacy Policy.",
            text.text,
        )
    }
}
