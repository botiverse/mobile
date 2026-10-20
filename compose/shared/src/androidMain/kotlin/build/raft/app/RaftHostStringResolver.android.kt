package build.raft.app

import build.raft.app.data.RaftAndroidSqlDatabaseContext
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

internal actual fun raftResolveHostString(desc: StringDesc): String =
    desc.toString(RaftAndroidSqlDatabaseContext.require())
