package ai.slock.compose

import android.content.Context
import ai.slock.compose.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.desc

fun localizedThreadComposerInsertNewlineTitle(context: Context): String =
    MR.strings.thread_composer_insert_newline.desc().toString(context)
