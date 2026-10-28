package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SqliteUserVersionPreflightTest {
    @Test
    fun mainDatabaseVersionIsReturnedWhenWalIsMissing() {
        val database = sqlitePageOne(userVersion = 7)

        assertEquals(
            7L,
            readSqliteUserVersionFromFiles(database.asReadOnlyFile(), wal = null),
        )
    }

    @Test
    fun anEmptyWalFileIsTreatedAsAbsent() {
        // SQLite leaves a zero-length -wal behind after a clean close (the WAL is truncated to
        // zero, not deleted). The preflight must read the main database's version in that case;
        // treating the empty file as a WAL to parse aborts the read.
        val database = sqlitePageOne(userVersion = 10)

        assertEquals(
            10L,
            readSqliteUserVersionFromFiles(database.asReadOnlyFile(), ByteArray(0).asReadOnlyFile()),
        )
    }

    @Test
    fun lastCommittedWalPageOneWinsForBothChecksumByteOrders() {
        TestWalChecksumOrder.entries.forEach { order ->
            val database = sqlitePageOne(userVersion = 7)
            val wal =
                sqliteWal(
                    order = order,
                    frames =
                        listOf(
                            TestWalFrame(page = sqlitePageOne(userVersion = 9), committed = true),
                            TestWalFrame(
                                page =
                                    sqlitePageOne(userVersion = 12).also { page ->
                                        page[0] = 0
                                    },
                                committed = false,
                            ),
                        ),
                )

            assertEquals(
                9L,
                readSqliteUserVersionFromFiles(database.asReadOnlyFile(), wal.asReadOnlyFile()),
                "order=$order",
            )
        }
    }

    @Test
    fun invalidTrailingWalFrameIsIgnoredAfterTheLastValidCommit() {
        val database = sqlitePageOne(userVersion = 7)
        val wal =
            sqliteWal(
                order = TestWalChecksumOrder.LittleEndian,
                frames =
                    listOf(
                        TestWalFrame(page = sqlitePageOne(userVersion = 9), committed = true),
                        TestWalFrame(page = sqlitePageOne(userVersion = 12), committed = true),
                    ),
            ).also { bytes ->
                bytes[32 + TestWalFrameSize + 24 + 60] =
                    (bytes[32 + TestWalFrameSize + 24 + 60].toInt() xor 0x01).toByte()
            }

        assertEquals(
            9L,
            readSqliteUserVersionFromFiles(database.asReadOnlyFile(), wal.asReadOnlyFile()),
        )
    }

    @Test
    fun invalidWalHeaderFailsClosed() {
        val database = sqlitePageOne(userVersion = 7)
        val wal =
            sqliteWal(
                order = TestWalChecksumOrder.LittleEndian,
                frames = emptyList(),
            ).also { bytes -> bytes[24] = (bytes[24].toInt() xor 0x01).toByte() }

        assertFails {
            readSqliteUserVersionFromFiles(database.asReadOnlyFile(), wal.asReadOnlyFile())
        }
    }
}

private data class TestWalFrame(
    val page: ByteArray,
    val committed: Boolean,
)

private enum class TestWalChecksumOrder(
    val magic: UInt,
) {
    LittleEndian(0x377f0682u),
    BigEndian(0x377f0683u),
}

private data class TestWalChecksum(
    val first: UInt,
    val second: UInt,
)

private fun sqlitePageOne(userVersion: Int): ByteArray =
    ByteArray(TestSqlitePageSize).also { page ->
        "SQLite format 3\u0000".forEachIndexed { index, character ->
            page[index] = character.code.toByte()
        }
        page.writeUnsignedShortBigEndian(index = 16, value = TestSqlitePageSize)
        page.writeUnsignedIntBigEndian(index = 60, value = userVersion.toUInt())
    }

private fun sqliteWal(
    order: TestWalChecksumOrder,
    frames: List<TestWalFrame>,
): ByteArray {
    val wal = ByteArray(32 + TestWalFrameSize * frames.size)
    wal.writeUnsignedIntBigEndian(index = 0, value = order.magic)
    wal.writeUnsignedIntBigEndian(index = 4, value = 3_007_000u)
    wal.writeUnsignedIntBigEndian(index = 8, value = TestSqlitePageSize.toUInt())
    wal.writeUnsignedIntBigEndian(index = 16, value = TestWalSaltFirst)
    wal.writeUnsignedIntBigEndian(index = 20, value = TestWalSaltSecond)
    var checksum = wal.testWalChecksum(startIndex = 0, byteCount = 24, order = order)
    wal.writeUnsignedIntBigEndian(index = 24, value = checksum.first)
    wal.writeUnsignedIntBigEndian(index = 28, value = checksum.second)

    frames.forEachIndexed { frameIndex, frame ->
        check(frame.page.size == TestSqlitePageSize)
        val frameOffset = 32 + frameIndex * TestWalFrameSize
        wal.writeUnsignedIntBigEndian(index = frameOffset, value = 1u)
        wal.writeUnsignedIntBigEndian(
            index = frameOffset + 4,
            value = if (frame.committed) 1u else 0u,
        )
        wal.writeUnsignedIntBigEndian(index = frameOffset + 8, value = TestWalSaltFirst)
        wal.writeUnsignedIntBigEndian(index = frameOffset + 12, value = TestWalSaltSecond)
        frame.page.copyInto(wal, destinationOffset = frameOffset + 24)
        checksum =
            wal.testWalChecksum(
                startIndex = frameOffset,
                byteCount = 8,
                order = order,
                initial = checksum,
            ).let { headerChecksum ->
                wal.testWalChecksum(
                    startIndex = frameOffset + 24,
                    byteCount = TestSqlitePageSize,
                    order = order,
                    initial = headerChecksum,
                )
            }
        wal.writeUnsignedIntBigEndian(index = frameOffset + 16, value = checksum.first)
        wal.writeUnsignedIntBigEndian(index = frameOffset + 20, value = checksum.second)
    }
    return wal
}

private fun ByteArray.testWalChecksum(
    startIndex: Int,
    byteCount: Int,
    order: TestWalChecksumOrder,
    initial: TestWalChecksum = TestWalChecksum(first = 0u, second = 0u),
): TestWalChecksum {
    var first = initial.first
    var second = initial.second
    var index = startIndex
    repeat(byteCount / 8) {
        first += readTestUnsignedInt(index, order) + second
        second += readTestUnsignedInt(index + 4, order) + first
        index += 8
    }
    return TestWalChecksum(first, second)
}

private fun ByteArray.readTestUnsignedInt(
    index: Int,
    order: TestWalChecksumOrder,
): UInt =
    when (order) {
        TestWalChecksumOrder.LittleEndian ->
            byteUnsigned(index) or
                (byteUnsigned(index + 1) shl 8) or
                (byteUnsigned(index + 2) shl 16) or
                (byteUnsigned(index + 3) shl 24)

        TestWalChecksumOrder.BigEndian ->
            (byteUnsigned(index) shl 24) or
                (byteUnsigned(index + 1) shl 16) or
                (byteUnsigned(index + 2) shl 8) or
                byteUnsigned(index + 3)
    }

private fun ByteArray.writeUnsignedIntBigEndian(
    index: Int,
    value: UInt,
) {
    this[index] = (value shr 24).toByte()
    this[index + 1] = (value shr 16).toByte()
    this[index + 2] = (value shr 8).toByte()
    this[index + 3] = value.toByte()
}

private fun ByteArray.writeUnsignedShortBigEndian(
    index: Int,
    value: Int,
) {
    this[index] = (value shr 8).toByte()
    this[index + 1] = value.toByte()
}

private fun ByteArray.byteUnsigned(index: Int): UInt =
    (this[index].toInt() and 0xff).toUInt()

private fun ByteArray.asReadOnlyFile(): SqliteReadOnlyFile =
    object : SqliteReadOnlyFile {
        override val size: Long = this@asReadOnlyFile.size.toLong()

        override fun read(
            offset: Long,
            byteCount: Int,
        ): ByteArray = copyOfRange(offset.toInt(), offset.toInt() + byteCount)
    }

private const val TestSqlitePageSize = 512
private const val TestWalFrameSize = 24 + TestSqlitePageSize
private const val TestWalSaltFirst = 0x12345678u
private const val TestWalSaltSecond = 0x90abcdefu
