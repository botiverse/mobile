package build.raft.app

import build.raft.app.data.HomeServerDrawerCommandIntent

internal enum class HomeServerDrawerCommandPhase {
    IdleClosed,
    SubmittedOpen,
    SettledOpen,
    SubmittedClose,
    GestureCloseDispatched
}

internal enum class HomeServerDrawerCommandTerminalKind {
    SettledOpen,
    SettledClosed
}

internal data class HomeServerDrawerCommandTerminal(
    val generation: Long,
    val kind: HomeServerDrawerCommandTerminalKind
)

internal data class HomeServerDrawerCommandState(
    val currentIntent: HomeServerDrawerCommandIntent = HomeServerDrawerCommandIntent(),
    val phase: HomeServerDrawerCommandPhase = HomeServerDrawerCommandPhase.IdleClosed,
    val rowReadyGeneration: Long? = null,
    val rowReadyKey: String? = null,
    val interactiveGeneration: Long? = null,
    val interactiveRowKey: String? = null,
    val terminals: List<HomeServerDrawerCommandTerminal> = emptyList(),
    val gestureDismissCount: Int = 0
)

internal fun HomeServerDrawerCommandState.submit(
    intent: HomeServerDrawerCommandIntent
): HomeServerDrawerCommandState =
    copy(
        currentIntent = intent,
        phase =
            if (intent.desiredOpen) {
                HomeServerDrawerCommandPhase.SubmittedOpen
            } else {
                HomeServerDrawerCommandPhase.SubmittedClose
            },
        rowReadyGeneration = null,
        rowReadyKey = null,
        interactiveGeneration = null,
        interactiveRowKey = null
    )

internal fun HomeServerDrawerCommandState.settle(
    generation: Long,
    desiredOpen: Boolean
): HomeServerDrawerCommandState {
    if (generation != currentIntent.generation || desiredOpen != currentIntent.desiredOpen) return this
    val expectedPhase =
        if (desiredOpen) HomeServerDrawerCommandPhase.SubmittedOpen else HomeServerDrawerCommandPhase.SubmittedClose
    if (phase != expectedPhase) return this
    val terminal =
        HomeServerDrawerCommandTerminal(
            generation = generation,
            kind =
                if (desiredOpen) {
                    HomeServerDrawerCommandTerminalKind.SettledOpen
                } else {
                    HomeServerDrawerCommandTerminalKind.SettledClosed
                }
        )
    return if (desiredOpen) {
        copy(
            phase = HomeServerDrawerCommandPhase.SettledOpen,
            interactiveGeneration = generation.takeIf { rowReadyGeneration == generation },
            interactiveRowKey = rowReadyKey.takeIf { rowReadyGeneration == generation },
            terminals = terminals + terminal
        )
    } else {
        copy(
            phase = HomeServerDrawerCommandPhase.IdleClosed,
            rowReadyGeneration = null,
            rowReadyKey = null,
            interactiveGeneration = null,
            interactiveRowKey = null,
            terminals = terminals + terminal
        )
    }
}

internal fun HomeServerDrawerCommandState.rowLaidOut(
    generation: Long,
    rowKey: String
): HomeServerDrawerCommandState {
    if (generation != currentIntent.generation || !currentIntent.desiredOpen) return this
    if (interactiveGeneration == generation) return this
    return if (phase == HomeServerDrawerCommandPhase.SettledOpen) {
        copy(
            rowReadyGeneration = generation,
            rowReadyKey = rowKey,
            interactiveGeneration = generation,
            interactiveRowKey = rowKey
        )
    } else {
        copy(rowReadyGeneration = generation, rowReadyKey = rowKey)
    }
}

internal fun HomeServerDrawerCommandState.nativeSettledClosedWithoutCommand(): HomeServerDrawerCommandState =
    if (phase == HomeServerDrawerCommandPhase.SettledOpen && currentIntent.desiredOpen) {
        copy(
            phase = HomeServerDrawerCommandPhase.GestureCloseDispatched,
            gestureDismissCount = gestureDismissCount + 1
        )
    } else {
        this
    }

internal fun HomeServerDrawerCommandState.acceptsRowObservation(
    candidateGeneration: Long,
    authoritativeIntent: HomeServerDrawerCommandIntent
): Boolean =
    authoritativeIntent.desiredOpen &&
        candidateGeneration == authoritativeIntent.generation &&
        candidateGeneration == currentIntent.generation &&
        currentIntent.desiredOpen

internal fun HomeServerDrawerCommandState.acceptsConversationClick(
    candidateGeneration: Long,
    authoritativeIntent: HomeServerDrawerCommandIntent
): Boolean =
    acceptsRowObservation(candidateGeneration, authoritativeIntent) &&
        phase == HomeServerDrawerCommandPhase.SettledOpen &&
        interactiveGeneration == candidateGeneration
