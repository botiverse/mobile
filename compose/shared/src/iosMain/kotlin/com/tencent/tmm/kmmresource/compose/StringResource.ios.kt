/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.tencent.tmm.kmmresource.resource.PluralsResource
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.Plural
import com.tencent.tmm.kmmresource.resource.desc.Resource
import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

// task #423: iOS must localize via `StringDesc.localized()` (NSBundle-backed),
// NOT `toString()`. Unlike Android — whose `StringDesc.toString()` is
// overridden to resolve the string — the Apple `ResourceStringDesc` only
// implements `localized()`, so `toString()` returned the data-class description
// ("ResourceStringDesc(...)") and leaked as a "RESOURCESTRING" placeholder in
// the UI (e.g. the home tab labels). `.localized()` binds to the StringDesc
// interface member (real localization), not the compose extension.
@Composable
actual fun stringResource(resource: StringResource): String {
    val locale by KmmResourceLocaleRuntime.locale.collectAsState()
    return localized(locale, StringDesc.Resource(resource))
}

@Composable
actual fun stringResource(resource: StringResource, vararg args: Any): String {
    val locale by KmmResourceLocaleRuntime.locale.collectAsState()
    return localized(locale, StringDesc.ResourceFormatted(resource, args.toList()))
}

@Composable
actual fun stringResource(resource: PluralsResource, quantity: Int): String {
    val locale by KmmResourceLocaleRuntime.locale.collectAsState()
    return localized(locale, StringDesc.Plural(resource, quantity))
}

/**
 * The Apple KmmResource implementation stores [StringDesc.localeType] in a
 * Kotlin/Native `@ThreadLocal`. Keep the assignment and lookup in one ordinary
 * synchronous call: a separate Unit-returning composable may be skipped while
 * a retained child composition resumes on a thread whose locale is still
 * `System`, producing mixed-language content in the same screen.
 */
private fun localized(locale: AppLocale?, desc: StringDesc): String {
    locale?.let { StringDesc.localeType = it.stringDescLocaleType }
    return desc.localized()
}
