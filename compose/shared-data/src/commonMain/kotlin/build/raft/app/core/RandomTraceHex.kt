package build.raft.app

import kotlin.random.Random

@RaftCoreInternalApi fun randomTraceHex(bytes: Int, random: Random = Random): String {
    val digits = "0123456789abcdef"
    return buildString(bytes * 2) {
        repeat(bytes) {
            val b = random.nextInt(256)
            append(digits[b shr 4])
            append(digits[b and 0xF])
        }
    }
}
