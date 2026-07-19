package build.raft.app

import build.raft.app.data.SlockAndroidSqlDatabaseContext
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

internal actual fun slockResolveHostString(desc: StringDesc): String =
    desc.toString(SlockAndroidSqlDatabaseContext.require())
