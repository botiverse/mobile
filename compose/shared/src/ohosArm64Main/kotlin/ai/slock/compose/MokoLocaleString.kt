package ai.slock.compose

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.desc.StringDesc

/**
 * OHOS actual for [StringDesc.localized]. The KBA fork's OHOS
 * `StringDesc` actual exposes a no-arg `toString()` that resolves
 * against the persisted resource name via the
 * `moko:resources-ohosarm64` runtime, picking the matching
 * `res/{base,zh_CN}/element/string.json` entry based on
 * `StringDesc.localeType`.
 */
@Composable
actual fun StringDesc.localized(): String = toString()
