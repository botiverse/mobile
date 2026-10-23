package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.AttachmentPreviewContent
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Server-parsed CSV preview (`GET attachments/:id/preview`, `kind: "csv"`; slock
 * `packages/server/src/services/attachmentPreviews/providers/csv.ts`). The server does the
 * parsing (quotes, `""` escapes, delimiter detection among `,` `\t` `;`), keeps the first row as
 * the header and caps the payload at [RaftFilePreviewCsvMaxRows] rows and
 * [RaftFilePreviewCsvMaxColumns] columns. The client renders it as the web `CsvPreviewPane` does.
 */
internal const val RaftFilePreviewCsvMaxRows = 200
internal const val RaftFilePreviewCsvMaxColumns = 12

/** The CSV table as carried on [RaftFilePreviewPayload.csvTableJson]. */
@Serializable
internal data class RaftFilePreviewCsvCarrier(
    val headers: List<String>,
    val rows: List<List<String>>,
    /** Data rows in the whole file (the header row not counted). */
    val rowCount: Int,
    /** Widest row in the whole file, before the column cap. */
    val columnCount: Int,
    /** Response `truncated` (server: stream cut or fewer rows than [rowCount]). */
    val truncated: Boolean,
)

private val RaftFilePreviewTableJson = Json { ignoreUnknownKeys = true }

/**
 * The carrier for a server CSV preview, or null when there is nothing to draw as a table (no
 * header row): the caller then falls back as for an unsupported preview. Rows and columns are
 * capped as the server caps them; a cap that drops rows marks the preview truncated, the same
 * rule as the server's `isAttachmentPreviewTruncated`.
 */
internal fun raftFilePreviewCsvCarrier(
    csv: AttachmentPreviewContent.Csv,
    responseTruncated: Boolean,
): RaftFilePreviewCsvCarrier? {
    if (csv.headers.isEmpty()) return null
    val rows = csv.rows.take(RaftFilePreviewCsvMaxRows).map { it.take(RaftFilePreviewCsvMaxColumns) }
    val rowCount = maxOf(csv.rowCount, csv.rows.size)
    return RaftFilePreviewCsvCarrier(
        headers = csv.headers.take(RaftFilePreviewCsvMaxColumns),
        rows = rows,
        rowCount = rowCount,
        columnCount = maxOf(csv.columnCount, csv.headers.size),
        truncated = responseTruncated || rows.size < rowCount,
    )
}

internal fun encodeRaftFilePreviewCsv(carrier: RaftFilePreviewCsvCarrier): String =
    runCatching {
        RaftFilePreviewTableJson.encodeToString(RaftFilePreviewCsvCarrier.serializer(), carrier)
    }.getOrDefault("")

internal fun parseRaftFilePreviewCsv(json: String): RaftFilePreviewCsvCarrier? =
    runCatching {
        RaftFilePreviewTableJson.decodeFromString(RaftFilePreviewCsvCarrier.serializer(), json)
    }.getOrNull()?.takeIf { it.headers.isNotEmpty() }

/**
 * Web `isCsvAttachment` (packages/web/src/components/message/attachmentPreview.ts): a `.csv`
 * name or a CSV MIME. `application/vnd.ms-excel` counts too (spreadsheet apps label CSV exports
 * with it) unless the name is a legacy binary Excel file; the server inspects the bytes and
 * answers unsupported for a binary workbook.
 */
internal fun raftFilePreviewIsCsvAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val normalizedName = fileName.substringBefore('?').substringBefore('#').trim().lowercase()
    if (RaftFilePreviewLegacyExcelExtensions.any { normalizedName.endsWith(it) }) return false
    return normalizedName.endsWith(".csv") || normalizedMime in RaftFilePreviewCsvMimeTypes
}

private val RaftFilePreviewLegacyExcelExtensions = listOf(".xls", ".xlt", ".xla", ".xlm", ".xlc", ".xlw")

private val RaftFilePreviewCsvMimeTypes = setOf("text/csv", "application/csv", "application/vnd.ms-excel")

/**
 * Web `CsvPreviewPane`: "CSV data preview" and the row / column count over the table card, the
 * table (header row, striped rows, wide tables pan sideways), and the truncation or
 * "Showing X of Y rows" line under it.
 */
@Composable
internal fun RaftFilePreviewCsvPanel(
    csv: RaftFilePreviewCsvCarrier,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val style = RaftTheme.typography.monoMeta
    val shownRows = csv.rows.size
    val rowsLabel =
        if (csv.truncated) {
            stringResource(if (shownRows == 1) MR.strings.file_preview_csv_rows_first_one else MR.strings.file_preview_csv_rows_first_other, shownRows)
        } else {
            stringResource(if (csv.rowCount == 1) MR.strings.file_preview_csv_rows_one else MR.strings.file_preview_csv_rows_other, csv.rowCount)
        }
    val columnsLabel =
        stringResource(
            if (csv.columnCount == 1) MR.strings.file_preview_csv_columns_one else MR.strings.file_preview_csv_columns_other,
            csv.columnCount,
        )
    val footer =
        if (csv.truncated) {
            stringResource(MR.strings.file_preview_csv_truncated, shownRows)
        } else {
            stringResource(MR.strings.file_preview_csv_showing_of, shownRows, csv.rowCount)
        }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.layerSidebar)
            .padding(RaftSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = RaftSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftText(
                text = stringResource(MR.strings.file_preview_csv_data),
                modifier = Modifier.weight(1f),
                style = style,
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RaftText(
                text = stringResource(MR.strings.file_preview_csv_rows_columns, rowsLabel, columnsLabel),
                style = style,
                color = colors.foregroundHint,
                maxLines = 1
            )
        }
        RaftCard(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            variant = RaftCardVariant.Inset
        ) {
            RaftFilePreviewDataTable(
                headers = csv.headers,
                rows = csv.rows,
                columnCount = csv.headers.size,
                modifier = Modifier.fillMaxWidth()
            )
        }
        RaftText(
            text = footer,
            modifier = Modifier.padding(top = RaftSpacing.Sm),
            style = style,
            color = colors.foregroundHint
        )
    }
}

/** Below this a column would cut most cells; wider tables pan sideways instead. */
private val RaftFilePreviewTableMinColumnWidth = 120.dp

/**
 * The file preview's data table (CSV and XLSX; web `<table>` in `CsvPreviewPane` /
 * `XlsxPreviewPane`): a pinned `fill-muted` header row in bold, `line-hairline` cell rules,
 * every other row on half `fill-muted`, cells in `mono-meta` on one line with an ellipsis.
 * Columns share the width; under [RaftFilePreviewTableMinColumnWidth] each, the table keeps
 * that width and pans sideways. Rows are lazy. An empty header shows "Column N". No
 * programmatic scroll: both axes move only under the user's finger.
 */
@Composable
internal fun RaftFilePreviewDataTable(
    headers: List<String>,
    rows: List<List<String>>,
    columnCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val style = RaftTheme.typography.monoMeta
    val columns = columnCount.coerceAtLeast(1)
    val headerLabels =
        List(columns) { index ->
            headers.getOrNull(index).orEmpty().ifEmpty { stringResource(MR.strings.file_preview_table_column_fallback, index + 1) }
        }
    val stripe = colors.fillMuted.copy(alpha = colors.fillMuted.alpha * 0.5f)
    BoxWithConstraints(modifier) {
        val columnWidth = maxOf(RaftFilePreviewTableMinColumnWidth, maxWidth / columns)
        val tableWidth = columnWidth * columns
        Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth)) {
            RaftFilePreviewTableRow(
                cells = headerLabels,
                columns = columns,
                columnWidth = columnWidth,
                background = colors.fillMuted,
                rule = colors.lineHairline,
                bottomRule = rows.isNotEmpty(),
                style = style.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground
            )
            LazyColumn(Modifier.width(tableWidth)) {
                items(count = rows.size, key = { index -> index }, contentType = { "row" }) { index ->
                    RaftFilePreviewTableRow(
                        cells = rows[index],
                        columns = columns,
                        columnWidth = columnWidth,
                        background = if (index % 2 == 0) stripe else Color.Transparent,
                        rule = colors.lineHairline,
                        bottomRule = index < rows.lastIndex,
                        style = style,
                        color = colors.foreground
                    )
                }
            }
        }
    }
}

@Composable
private fun RaftFilePreviewTableRow(
    cells: List<String>,
    columns: Int,
    columnWidth: Dp,
    background: Color,
    rule: Color,
    bottomRule: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
) {
    Row(Modifier.background(background).tableRules(rule, end = false, bottom = bottomRule)) {
        repeat(columns) { column ->
            // Kuikly Text Decoration Rule: padding and rules on the wrapper, never on the text.
            Box(
                Modifier
                    .width(columnWidth)
                    .tableRules(rule, end = column < columns - 1, bottom = false)
                    .padding(horizontal = RaftSpacing.Sm, vertical = RaftSpacing.Xs)
            ) {
                RaftText(
                    text = cells.getOrNull(column).orEmpty(),
                    style = style,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Hairline cell rules on the end and bottom edges (CSS `border-collapse`: one line between cells). */
private fun Modifier.tableRules(color: Color, end: Boolean, bottom: Boolean): Modifier =
    if (!end && !bottom) {
        this
    } else {
        drawBehind {
            val stroke = RaftStroke.Hairline.toPx()
            if (end) {
                val x = size.width - stroke / 2
                drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            }
            if (bottom) {
                val y = size.height - stroke / 2
                drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
            }
        }
    }
