package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule

internal actual fun createSlockRelationalStore(bridge: BridgeModule): SlockRelationalStore =
    SqlDelightOhosRelationalStore()
