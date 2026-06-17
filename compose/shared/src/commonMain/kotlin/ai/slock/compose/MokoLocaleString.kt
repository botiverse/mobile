package ai.slock.compose

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.desc.StringDesc

/**
 * #85 follow-up: bridge `StringDesc` (moko KBA typed) to a Compose-friendly
 * `String` so Compose `Text(...)` calls can render localized content
 * without each call site having to grab a Context.
 *
 * The platform `actual` provides the resolution path:
 *   - Android: `LocalContext.current` + `StringDesc.toString(context)`
 *   - OHOS: `StringDesc.toString()` (no-arg) — the KBA fork's OHOS
 *     `StringDesc` actual resolves against the persisted resource
 *     name via the `moko:resources-ohosarm64` runtime.
 *
 * We deliberately don't add `dev.icerock.moko:resources-compose` as a
 * dep (it's not in the KBA m2 mirror) and instead inline a tiny
 * `expect/actual` here to keep the dep surface minimal.
 */
@Composable
expect fun StringDesc.localized(): String
