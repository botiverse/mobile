/*
 * Copyright 2022 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import ai.slock.compose.data.SlockAndroidSqlDatabaseContext
import androidx.compose.runtime.Composable
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

@Composable
actual fun StringDesc.localized(): String =
    toString(SlockAndroidSqlDatabaseContext.require())
