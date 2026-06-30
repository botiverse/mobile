package build.raft.app

import android.content.Context
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.desc

fun localizedThreadComposerInsertNewlineTitle(context: Context): String =
    MR.strings.thread_composer_insert_newline.desc().toString(context)
