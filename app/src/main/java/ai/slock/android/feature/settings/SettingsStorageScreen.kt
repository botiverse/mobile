package ai.slock.android.feature.settings

import ai.slock.android.R
import ai.slock.android.core.data.storage.StorageClearTarget
import ai.slock.android.core.data.storage.StorageManagementSnapshot
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.components.SlockKeySurface
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun SettingsStorageScreen(
        snapshot: StorageManagementSnapshot,
        actionInProgress: StorageClearTarget? = null,
        feedback: String? = null,
        modifier: Modifier = Modifier,
        onRefresh: () -> Unit = {},
        onClear: (StorageClearTarget) -> Unit = {}
) {
    Column(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .verticalScroll(rememberScrollState())
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsStorageSummary(snapshot = snapshot, onRefresh = onRefresh)
        SettingsStorageRow(
                icon = LucideIcons.Archive,
                title = stringResource(R.string.settings_storage_database_title),
                detail = stringResource(R.string.settings_storage_database_detail),
                size = snapshot.localDatabaseBytes.formatStorageSize(),
                clearLabel = null,
                clearInProgress = false,
                onClear = {}
        )
        SettingsStorageRow(
                icon = LucideIcons.Image,
                title = stringResource(R.string.settings_storage_image_cache_title),
                detail = stringResource(R.string.settings_storage_image_cache_detail),
                size = snapshot.imageCacheBytes.formatStorageSize(),
                clearLabel = stringResource(R.string.settings_storage_clear_image_cache),
                clearInProgress = actionInProgress == StorageClearTarget.ImageCache,
                clearEnabled = snapshot.imageCacheBytes > 0L,
                onClear = { onClear(StorageClearTarget.ImageCache) }
        )
        SettingsStorageRow(
                icon = LucideIcons.FileText,
                title = stringResource(R.string.settings_storage_temp_logs_title),
                detail = stringResource(R.string.settings_storage_temp_logs_detail),
                size = snapshot.temporaryAndLogBytes.formatStorageSize(),
                clearLabel = stringResource(R.string.settings_storage_clear_temp_logs),
                clearInProgress = actionInProgress == StorageClearTarget.TemporaryAndLogs,
                clearEnabled = snapshot.temporaryAndLogBytes > 0L,
                onClear = { onClear(StorageClearTarget.TemporaryAndLogs) }
        )
        Text(
                text = stringResource(R.string.settings_storage_safe_clear_note),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
        )
        feedback?.let { SettingsStorageFeedback(it) }
    }
}

@Composable
private fun SettingsStorageSummary(snapshot: StorageManagementSnapshot, onRefresh: () -> Unit) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.BrandYellow)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
                text = stringResource(R.string.settings_storage_total_title),
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack
        )
        Text(
                text = snapshot.totalBytes.formatStorageSize(),
                style = SlockType.PageTitle.copy(fontWeight = FontWeight.Black),
                color = SlockColor.TextBlack
        )
        SlockButton(
                text = stringResource(R.string.settings_storage_refresh),
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Outline,
                leadingContent = {
                    Icon(
                            imageVector = LucideIcons.RefreshCw,
                            contentDescription = null,
                            tint = SlockColor.TextBlack,
                            modifier = Modifier.size(16.dp)
                    )
                }
        )
    }
}

@Composable
private fun SettingsStorageRow(
        icon: ImageVector,
        title: String,
        detail: String,
        size: String,
        clearLabel: String?,
        clearInProgress: Boolean,
        clearEnabled: Boolean = false,
        onClear: () -> Unit
) {
    SlockKeySurface(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            restingShadow = SlockShadow.Small,
            pressedShadow = SlockShadow.None,
            enabled = false,
            dimmed = false,
            shape = RectangleShape
    ) {
        Column(
                modifier = Modifier.padding(SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SlockColor.TextBlack,
                        modifier = Modifier.size(22.dp)
                )
                Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                            text = title,
                            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )
                    Text(
                            text = detail,
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray
                    )
                }
                Text(
                        text = size,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextBlack
                )
            }
            if (clearLabel != null) {
                SlockButton(
                        text = clearLabel,
                        onClick = onClear,
                        modifier = Modifier.fillMaxWidth(),
                        variant = SlockButtonVariant.Outline,
                        enabled = clearEnabled,
                        loading = clearInProgress,
                        leadingContent = {
                            Icon(
                                    imageVector = LucideIcons.Trash,
                                    contentDescription = null,
                                    tint = SlockColor.TextBlack,
                                    modifier = Modifier.size(16.dp)
                            )
                        }
                )
            }
        }
    }
}

@Composable
private fun SettingsStorageFeedback(text: String) {
    Text(
            text = text,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.AccentCyan)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(SlockSpacing.Md)
    )
}

private fun Long.formatStorageSize(): String {
    if (this <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsStorageScreenPreview() {
    SlockTheme {
        SettingsStorageScreen(
                snapshot =
                        StorageManagementSnapshot(
                                localDatabaseBytes = 4_812_032,
                                imageCacheBytes = 18_251_776,
                                temporaryAndLogBytes = 524_288
                        ),
                feedback = "Storage refreshed."
        )
    }
}
