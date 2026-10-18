/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.applyAppLocaleOverride
import build.raft.app.data.AppLocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.tencent.tmm.kmmresource.resource.PluralsResource
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.Plural
import com.tencent.tmm.kmmresource.resource.desc.Resource
import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

// task #190: like the iOS actual, subscribe to the published app locale so
// Compose recomposes on a language change, and push the tag to the ets host
// (KNOI `slockOhosAppLocale`) so `resourceManager` string lookups run through
// the locale override. The OHOS `StringDesc.toString()` path itself carries
// no locale, so resolution alone would otherwise keep following the system
// locale. `applyAppLocaleOverride` dedupes and retries when the provider is
// not registered yet, so calling it per resolution is cheap.
@Composable
actual fun stringResource(resource: StringResource): String =
    localized(StringDesc.Resource(resource))

@Composable
actual fun stringResource(resource: StringResource, vararg args: Any): String =
    localized(StringDesc.ResourceFormatted(resource, args.toList()))

@Composable
actual fun stringResource(resource: PluralsResource, quantity: Int): String =
    localized(StringDesc.Plural(resource, quantity))

@Composable
private fun localized(desc: StringDesc): String {
    val locale: AppLocale? by KmmResourceLocaleRuntime.locale.collectAsState()
    locale?.let { applyAppLocaleOverride(it) }
    return desc.toString()
}
