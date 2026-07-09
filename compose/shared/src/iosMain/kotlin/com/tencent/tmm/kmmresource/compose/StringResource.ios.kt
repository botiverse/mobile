/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import androidx.compose.runtime.Composable
import com.tencent.tmm.kmmresource.resource.PluralsResource
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.Plural
import com.tencent.tmm.kmmresource.resource.desc.Resource
import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

// task #423: iOS must localize via `StringDesc.localized()` (NSBundle-backed),
// NOT `toString()`. Unlike Android/OHOS — whose `StringDesc.toString()` is
// overridden to resolve the string — the Apple `ResourceStringDesc` only
// implements `localized()`, so `toString()` returned the data-class description
// ("ResourceStringDesc(...)") and leaked as a "RESOURCESTRING" placeholder in
// the UI (e.g. the home tab labels). `.localized()` binds to the StringDesc
// interface member (real localization), not the compose extension.
@Composable
actual fun stringResource(resource: StringResource): String =
    StringDesc.Resource(resource).localized()

@Composable
actual fun stringResource(resource: StringResource, vararg args: Any): String =
    StringDesc.ResourceFormatted(resource, args.toList()).localized()

@Composable
actual fun stringResource(resource: PluralsResource, quantity: Int): String =
    StringDesc.Plural(resource, quantity).localized()
