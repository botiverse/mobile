package build.raft.app

import com.tencent.tmm.kmmresource.resource.desc.StringDesc

internal actual fun slockResolveHostString(desc: StringDesc): String = desc.localized()
