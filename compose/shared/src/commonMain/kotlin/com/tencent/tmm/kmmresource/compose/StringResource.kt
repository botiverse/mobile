/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */
package com.tencent.tmm.kmmresource.compose

import androidx.compose.runtime.Composable
import com.tencent.tmm.kmmresource.resource.PluralsResource
import com.tencent.tmm.kmmresource.resource.StringResource

@Composable
expect fun stringResource(resource: StringResource): String

@Composable
expect fun stringResource(resource: StringResource, vararg args: Any): String

@Composable
expect fun stringResource(
    resource: PluralsResource,
    quantity: Int
): String
