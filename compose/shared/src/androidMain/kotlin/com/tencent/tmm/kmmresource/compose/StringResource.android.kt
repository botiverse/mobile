/*
 * Copyright 2021 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import build.raft.app.data.RaftAndroidSqlDatabaseContext
import androidx.compose.runtime.Composable
import com.tencent.tmm.kmmresource.resource.PluralsResource
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.Plural
import com.tencent.tmm.kmmresource.resource.desc.Resource
import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

@Composable
actual fun stringResource(resource: StringResource): String =
    StringDesc.Resource(resource).toString(RaftAndroidSqlDatabaseContext.require())

@Composable
actual fun stringResource(resource: StringResource, vararg args: Any): String =
    StringDesc.ResourceFormatted(resource, args.toList()).toString(RaftAndroidSqlDatabaseContext.require())

@Composable
actual fun stringResource(resource: PluralsResource, quantity: Int): String =
    StringDesc.Plural(resource, quantity).toString(RaftAndroidSqlDatabaseContext.require())
