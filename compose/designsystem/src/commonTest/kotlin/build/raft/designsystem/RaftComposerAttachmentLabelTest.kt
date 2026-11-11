package build.raft.designsystem

import build.raft.designsystem.components.raftUnbreakableRuns
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The runs a composer attachment overlay label never breaks inside, which decide whether it
 * must shrink to stay in its tile: Latin words whole, every CJK character on its own (artin
 * 11-11: "正在完成上传…" was taken for one word wider than the image tile and spilled out).
 */
class RaftComposerAttachmentLabelTest {
    @Test
    fun splitsWordsAtSpacesAndCjkAtEveryCharacter() {
        assertEquals(listOf("Finishing", "upload…"), raftUnbreakableRuns("Finishing upload…"))
        assertEquals(listOf("正", "在", "完", "成", "上", "传", "…"), raftUnbreakableRuns("正在完成上传…"))
        assertEquals(listOf("ア", "ッ", "プ", "42%"), raftUnbreakableRuns("アップ 42%"))
        assertEquals(listOf("업", "로", "드", "중", "…"), raftUnbreakableRuns("업로드 중…"))
        assertEquals(emptyList<String>(), raftUnbreakableRuns("  "))
    }
}
