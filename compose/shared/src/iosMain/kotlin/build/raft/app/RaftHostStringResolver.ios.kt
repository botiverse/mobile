package build.raft.app

import com.tencent.tmm.kmmresource.resource.desc.StringDesc

internal actual fun raftResolveHostString(desc: StringDesc): String = desc.localized()
