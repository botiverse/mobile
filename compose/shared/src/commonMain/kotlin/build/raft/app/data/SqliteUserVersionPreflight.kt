package build.raft.app.data

/**
 * Minimal random-access surface used by the launch version preflight.
 *
 * Implementations must use file reads only. Opening the database through SQLite is intentionally
 * forbidden here: even a read-only SQLite connection may create or update the WAL shared-memory
 * file, while SQLite's immutable mode ignores committed WAL frames.
 */
internal interface SqliteReadOnlyFile {
    val size: Long

    fun read(
        offset: Long,
        byteCount: Int,
    ): ByteArray
}

/**
 * Reads the version visible after the last valid committed WAL frame without opening SQLite.
 *
 * SQLite stores [user_version] in page 1. In WAL mode the current page 1 may exist only in the
 * WAL, so reading the main-file header alone can return a stale version. This parser follows the
 * same checksum/salt/commit boundary that SQLite uses while recovering a WAL and ignores an
 * incomplete or invalid trailing frame.
 */
internal fun readSqliteUserVersionFromFiles(
    database: SqliteReadOnlyFile,
    wal: SqliteReadOnlyFile?,
): Long {
    val databaseHeader = database.readExact(offset = 0L, byteCount = SqliteDatabaseHeaderSize)
    val mainPage = parseSqlitePageOneHeader(databaseHeader)
    check(database.size >= mainPage.pageSize && database.size % mainPage.pageSize == 0L) {
        "Invalid SQLite database file size ${database.size} for page size ${mainPage.pageSize}"
    }
    // SQLite truncates the -wal file to zero bytes after a clean close instead of deleting it, and
    // a zero-length WAL carries no committed pages. Reading it as a WAL aborts the preflight, so
    // an empty one is treated as absent and the main database's version stands.
    if (wal == null || wal.size == 0L) return mainPage.userVersion

    check(wal.size >= SqliteWalHeaderSize) {
        "Invalid SQLite WAL: file is shorter than its header"
    }
    val walHeader = wal.readExact(offset = 0L, byteCount = SqliteWalHeaderSize)
    val checksumOrder =
        when (val magic = walHeader.readUnsignedIntBigEndian(0)) {
            SqliteWalLittleEndianChecksumMagic -> SqliteWalChecksumOrder.LittleEndian
            SqliteWalBigEndianChecksumMagic -> SqliteWalChecksumOrder.BigEndian
            else -> error("Invalid SQLite WAL magic 0x${magic.toString(16)}")
        }
    val walFormat = walHeader.readUnsignedIntBigEndian(4)
    check(walFormat == SqliteWalFormatVersion) {
        "Unsupported SQLite WAL format $walFormat"
    }
    val walPageSize = walHeader.readUnsignedIntBigEndian(8).toLong()
    check(isValidSqlitePageSize(walPageSize) && walPageSize == mainPage.pageSize) {
        "Invalid SQLite WAL page size $walPageSize for database page size ${mainPage.pageSize}"
    }

    var checksum =
        updateWalChecksum(
            bytes = walHeader,
            startIndex = 0,
            byteCount = SqliteWalHeaderChecksumInputSize,
            order = checksumOrder,
            initial = SqliteWalChecksum.Zero,
        )
    check(
        checksum.first == walHeader.readUnsignedIntBigEndian(24) &&
            checksum.second == walHeader.readUnsignedIntBigEndian(28)
    ) {
        "Invalid SQLite WAL header checksum"
    }

    val saltFirst = walHeader.readUnsignedIntBigEndian(16)
    val saltSecond = walHeader.readUnsignedIntBigEndian(20)
    val frameSize = SqliteWalFrameHeaderSize + walPageSize
    var frameOffset = SqliteWalHeaderSize.toLong()
    var committedVersion = mainPage.userVersion
    var pendingPageOne: ByteArray? = null

    while (wal.size - frameOffset >= frameSize) {
        val frameHeader =
            wal.readExact(
                offset = frameOffset,
                byteCount = SqliteWalFrameHeaderSize,
            )
        if (
            frameHeader.readUnsignedIntBigEndian(8) != saltFirst ||
            frameHeader.readUnsignedIntBigEndian(12) != saltSecond
        ) {
            break
        }

        val pageNumber = frameHeader.readUnsignedIntBigEndian(0)
        if (pageNumber == 0u) break
        val page =
            wal.readExact(
                offset = frameOffset + SqliteWalFrameHeaderSize,
                byteCount = walPageSize.toInt(),
            )
        val frameChecksum =
            updateWalChecksum(
                bytes = page,
                startIndex = 0,
                byteCount = page.size,
                order = checksumOrder,
                initial =
                    updateWalChecksum(
                        bytes = frameHeader,
                        startIndex = 0,
                        byteCount = SqliteWalFrameChecksumHeaderInputSize,
                        order = checksumOrder,
                        initial = checksum,
                    ),
            )
        if (
            frameChecksum.first != frameHeader.readUnsignedIntBigEndian(16) ||
            frameChecksum.second != frameHeader.readUnsignedIntBigEndian(20)
        ) {
            break
        }
        checksum = frameChecksum

        if (pageNumber == 1u) {
            pendingPageOne = page
        }
        if (frameHeader.readUnsignedIntBigEndian(4) != 0u) {
            pendingPageOne?.let { committedPageOne ->
                val walPageOne = parseSqlitePageOneHeader(committedPageOne)
                check(walPageOne.pageSize == mainPage.pageSize) {
                    "SQLite WAL page 1 changed the database page size"
                }
                committedVersion = walPageOne.userVersion
            }
            pendingPageOne = null
        }
        frameOffset += frameSize
    }

    return committedVersion
}

private data class SqlitePageOneHeader(
    val pageSize: Long,
    val userVersion: Long,
)

private fun parseSqlitePageOneHeader(bytes: ByteArray): SqlitePageOneHeader {
    check(bytes.size >= SqliteDatabaseHeaderSize) {
        "Invalid SQLite page 1: header is truncated"
    }
    check(
        SqliteDatabaseMagic.indices.all { index ->
            bytes[index] == SqliteDatabaseMagic[index].code.toByte()
        }
    ) {
        "Invalid SQLite database header"
    }
    val encodedPageSize = bytes.readUnsignedShortBigEndian(16)
    val pageSize = if (encodedPageSize == 1) SqliteMaximumPageSize else encodedPageSize.toLong()
    check(isValidSqlitePageSize(pageSize)) {
        "Invalid SQLite database page size $pageSize"
    }
    return SqlitePageOneHeader(
        pageSize = pageSize,
        userVersion = bytes.readSignedIntBigEndian(60).toLong(),
    )
}

private fun isValidSqlitePageSize(value: Long): Boolean =
    value in SqliteMinimumPageSize..SqliteMaximumPageSize && value and (value - 1L) == 0L

private data class SqliteWalChecksum(
    val first: UInt,
    val second: UInt,
) {
    companion object {
        val Zero = SqliteWalChecksum(first = 0u, second = 0u)
    }
}

private enum class SqliteWalChecksumOrder {
    LittleEndian,
    BigEndian,
}

private fun updateWalChecksum(
    bytes: ByteArray,
    startIndex: Int,
    byteCount: Int,
    order: SqliteWalChecksumOrder,
    initial: SqliteWalChecksum,
): SqliteWalChecksum {
    check(byteCount % 8 == 0) {
        "SQLite WAL checksum input must contain complete 32-bit word pairs"
    }
    check(startIndex >= 0 && byteCount >= 0 && startIndex <= bytes.size - byteCount) {
        "SQLite WAL checksum input is out of bounds"
    }
    var first = initial.first
    var second = initial.second
    var index = startIndex
    val end = startIndex + byteCount
    while (index < end) {
        val firstWord = bytes.readUnsignedInt(index, order)
        val secondWord = bytes.readUnsignedInt(index + 4, order)
        first += firstWord + second
        second += secondWord + first
        index += 8
    }
    return SqliteWalChecksum(first = first, second = second)
}

private fun ByteArray.readUnsignedInt(
    index: Int,
    order: SqliteWalChecksumOrder,
): UInt =
    when (order) {
        SqliteWalChecksumOrder.LittleEndian ->
            byteUnsigned(index) or
                (byteUnsigned(index + 1) shl 8) or
                (byteUnsigned(index + 2) shl 16) or
                (byteUnsigned(index + 3) shl 24)

        SqliteWalChecksumOrder.BigEndian -> readUnsignedIntBigEndian(index)
    }

private fun ByteArray.readUnsignedIntBigEndian(index: Int): UInt =
    (byteUnsigned(index) shl 24) or
        (byteUnsigned(index + 1) shl 16) or
        (byteUnsigned(index + 2) shl 8) or
        byteUnsigned(index + 3)

private fun ByteArray.readSignedIntBigEndian(index: Int): Int =
    readUnsignedIntBigEndian(index).toInt()

private fun ByteArray.readUnsignedShortBigEndian(index: Int): Int =
    ((this[index].toInt() and 0xff) shl 8) or (this[index + 1].toInt() and 0xff)

private fun ByteArray.byteUnsigned(index: Int): UInt =
    (this[index].toInt() and 0xff).toUInt()

private fun SqliteReadOnlyFile.readExact(
    offset: Long,
    byteCount: Int,
): ByteArray {
    check(offset >= 0L && byteCount >= 0 && offset <= size - byteCount.toLong()) {
        "SQLite file read is out of bounds"
    }
    return read(offset, byteCount).also { bytes ->
        check(bytes.size == byteCount) {
            "SQLite file read returned ${bytes.size} bytes instead of $byteCount"
        }
    }
}

private const val SqliteDatabaseMagic = "SQLite format 3\u0000"
private const val SqliteDatabaseHeaderSize = 100
private const val SqliteMinimumPageSize = 512L
private const val SqliteMaximumPageSize = 65_536L
private const val SqliteWalHeaderSize = 32
private const val SqliteWalFrameHeaderSize = 24
private const val SqliteWalHeaderChecksumInputSize = 24
private const val SqliteWalFrameChecksumHeaderInputSize = 8
private const val SqliteWalFormatVersion = 3_007_000u
private const val SqliteWalLittleEndianChecksumMagic = 0x377f0682u
private const val SqliteWalBigEndianChecksumMagic = 0x377f0683u
