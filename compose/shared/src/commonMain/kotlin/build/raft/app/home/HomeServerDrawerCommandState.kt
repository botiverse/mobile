package build.raft.app

import build.raft.app.data.HomeServerDrawerCommandIntent

internal enum class HomeServerDrawerCommandPhase {
    IdleClosed,
    PendingAdmissionOpen,
    SubmittedOpen,
    SettledOpen,
    PendingAdmissionClose,
    SubmittedClose,
    GestureCloseDispatched
}

internal enum class HomeServerDrawerCommandTerminalKind {
    SettledOpen,
    SettledClosed
}

internal enum class HomeServerDrawerInteractionState {
    ClosedAppEdgeOwner,
    OpeningProgrammatic,
    OpenNativePagerOwner,
    GestureCloseCommitting,
    ClosingProgrammatic
}

internal data class HomeServerDrawerPointerPolicy(
    val appEdgeEnabled: Boolean = false,
    val nativePagerEnabled: Boolean = false
)

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
    val gestureDismissCount: Int = 0,
    val interactionState: HomeServerDrawerInteractionState = HomeServerDrawerInteractionState.ClosedAppEdgeOwner,
    val ownerGeneration: Long? = null
)

internal fun HomeServerDrawerCommandState.pointerPolicy(
    authoritativeIntent: HomeServerDrawerCommandIntent
): HomeServerDrawerPointerPolicy {
    if (authoritativeIntent != currentIntent || ownerGeneration != authoritativeIntent.generation) {
        return HomeServerDrawerPointerPolicy()
    }
    return when (interactionState) {
        HomeServerDrawerInteractionState.ClosedAppEdgeOwner ->
            HomeServerDrawerPointerPolicy(appEdgeEnabled = !authoritativeIntent.desiredOpen)
        HomeServerDrawerInteractionState.OpenNativePagerOwner ->
            HomeServerDrawerPointerPolicy(nativePagerEnabled = authoritativeIntent.desiredOpen)
        HomeServerDrawerInteractionState.OpeningProgrammatic,
        HomeServerDrawerInteractionState.GestureCloseCommitting,
        HomeServerDrawerInteractionState.ClosingProgrammatic -> HomeServerDrawerPointerPolicy()
    }
}

internal fun HomeServerDrawerCommandState.establishClosedOwner(
    authoritativeIntent: HomeServerDrawerCommandIntent
): HomeServerDrawerCommandState =
    if (
        phase == HomeServerDrawerCommandPhase.IdleClosed &&
        !authoritativeIntent.desiredOpen &&
        authoritativeIntent == currentIntent
    ) {
        copy(
            interactionState = HomeServerDrawerInteractionState.ClosedAppEdgeOwner,
            ownerGeneration = authoritativeIntent.generation
        )
    } else {
        this
    }

internal fun HomeServerDrawerCommandState.beginProgrammaticClose(): HomeServerDrawerCommandState =
    if (currentIntent.desiredOpen) {
        copy(
            interactionState = HomeServerDrawerInteractionState.ClosingProgrammatic,
            ownerGeneration = null
        )
    } else {
        this
    }

internal fun HomeServerDrawerCommandState.beginProgrammaticOpen(): HomeServerDrawerCommandState =
    if (!currentIntent.desiredOpen) {
        copy(
            interactionState = HomeServerDrawerInteractionState.OpeningProgrammatic,
            ownerGeneration = null
        )
    } else {
        this
    }

internal fun HomeServerDrawerCommandState.pendingAdmission(
    intent: HomeServerDrawerCommandIntent
): HomeServerDrawerCommandState =
    copy(
        currentIntent = intent,
        phase =
            if (intent.desiredOpen) {
                HomeServerDrawerCommandPhase.PendingAdmissionOpen
            } else {
                HomeServerDrawerCommandPhase.PendingAdmissionClose
            },
        rowReadyGeneration = null,
        rowReadyKey = null,
        interactiveGeneration = null,
        interactiveRowKey = null,
        interactionState =
            if (intent.desiredOpen) {
                HomeServerDrawerInteractionState.OpeningProgrammatic
            } else {
                HomeServerDrawerInteractionState.ClosingProgrammatic
            },
        ownerGeneration = null
    )

internal fun HomeServerDrawerCommandState.markSubmitted(
    generation: Long,
    desiredOpen: Boolean
): HomeServerDrawerCommandState {
    if (generation != currentIntent.generation || desiredOpen != currentIntent.desiredOpen) return this
    val expectedPhase =
        if (desiredOpen) HomeServerDrawerCommandPhase.PendingAdmissionOpen else HomeServerDrawerCommandPhase.PendingAdmissionClose
    if (phase != expectedPhase) return this
    return copy(
        phase =
            if (desiredOpen) {
                HomeServerDrawerCommandPhase.SubmittedOpen
            } else {
                HomeServerDrawerCommandPhase.SubmittedClose
            }
    )
}

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
        interactiveRowKey = null,
        interactionState =
            if (intent.desiredOpen) {
                HomeServerDrawerInteractionState.OpeningProgrammatic
            } else {
                HomeServerDrawerInteractionState.ClosingProgrammatic
            },
        ownerGeneration = null
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
            terminals = terminals + terminal,
            interactionState = HomeServerDrawerInteractionState.OpenNativePagerOwner,
            ownerGeneration = generation
        )
    } else {
        copy(
            phase = HomeServerDrawerCommandPhase.IdleClosed,
            rowReadyGeneration = null,
            rowReadyKey = null,
            interactiveGeneration = null,
            interactiveRowKey = null,
            terminals = terminals + terminal,
            interactionState = HomeServerDrawerInteractionState.ClosedAppEdgeOwner,
            ownerGeneration = generation
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
    if (
        phase == HomeServerDrawerCommandPhase.SettledOpen &&
        currentIntent.desiredOpen &&
        interactionState == HomeServerDrawerInteractionState.OpenNativePagerOwner &&
        ownerGeneration == currentIntent.generation
    ) {
        copy(
            phase = HomeServerDrawerCommandPhase.GestureCloseDispatched,
            gestureDismissCount = gestureDismissCount + 1,
            interactionState = HomeServerDrawerInteractionState.GestureCloseCommitting,
            ownerGeneration = null
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
        interactionState == HomeServerDrawerInteractionState.OpenNativePagerOwner &&
        ownerGeneration == candidateGeneration &&
        interactiveGeneration == candidateGeneration
