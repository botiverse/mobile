/*
 * Copyright 2022 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import androidx.compose.runtime.Composable
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

/**
 * iOS-specific actual of [StringDesc.localized].
 *
 * On iOS the kmmresource fork's [StringDesc] interface (in appleMain) declares
 * `fun localized(): String` as a member, which is backed by NSBundle and returns
 * the truly-localized string. Kotlin's member-priority rule means the member
 * shadows this extension on iOS — but the expect/actual contract still requires
 * this `actual` to be present so the commonMain `expect fun` compiles.
 *
 * Therefore the body intentionally looks recursive: it calls the interface
 * member (not itself), preserving Kotlin's member-priority resolution.
 * Without this Kotlin would not let commonMain dispatch to the member directly,
 * because commonMain cannot see the appleMain interface declaration.
 *
 * Do NOT change to `toString()` — that would surface the data-class description
 * (the pre-#423 "RESOURCESTRING" style bug) on any future code path that
 * resolves the expect to this actual. Members always win; this is just plumbing.
 */
@Composable
actual fun StringDesc.localized(): String = this.localized()
