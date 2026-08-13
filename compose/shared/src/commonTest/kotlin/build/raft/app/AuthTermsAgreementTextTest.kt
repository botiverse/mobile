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
}
