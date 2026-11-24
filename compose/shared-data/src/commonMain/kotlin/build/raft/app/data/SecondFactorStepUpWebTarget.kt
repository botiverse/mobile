package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.sharedSettingsWebUrl

/**
 * The web page where a refused action is done, for "Verify it's you"'s Open on the web: when the
 * account answers only with a passkey this device can't use, the person makes the same change on
 * the web and confirms it there with the passkey. Routes are the web's (slock
 * `components/layout/MainLayout.tsx` MainColumnRoutes under `/s/:serverSlug`); the web has no
 * deep link that opens a dialog (Add Computer, Change password), so each opens the page that has it.
 */
sealed interface SecondFactorStepUpWebTarget {
    /** Settings › Account (password, sign-in providers, signed-in devices): `/settings/account`. */
    data object Account : SecondFactorStepUpWebTarget

    /** Adding a computer: the Computers page, whose Add Computer opens the dialog: `/computers`. */
    data object Computers : SecondFactorStepUpWebTarget

    /** Something on one computer (a new machine key): its page, `/computer/<id>`. */
    data class Computer(val machineId: String) : SecondFactorStepUpWebTarget

    /** A card in a conversation (Approve Login): the channel, `/channel/<id>`, or the DM, `/dm/<id>`. */
    data class Conversation(val channelId: String, val directMessage: Boolean) : SecondFactorStepUpWebTarget
}

/** [target]'s path on the web, under the server's `/s/<slug>`. */
@RaftCoreInternalApi fun SecondFactorStepUpWebTarget.webPath(): String =
    when (this) {
        SecondFactorStepUpWebTarget.Account -> "settings/account"
        SecondFactorStepUpWebTarget.Computers -> "computers"
        is SecondFactorStepUpWebTarget.Computer -> "computer/${machineId.trim()}"
        is SecondFactorStepUpWebTarget.Conversation -> "${if (directMessage) "dm" else "channel"}/${channelId.trim()}"
    }

/**
 * [target]'s web page on the Developer environment's [webBaseUrl] (AGENTS.md Environment Web Link
 * URL Rule; production when unset) in the server [serverSlug]. Without a server the account page is
 * `/settings/account` (the web picks the server); any other target opens the web's home.
 */
@RaftCoreInternalApi fun secondFactorStepUpWebUrl(
    webBaseUrl: String?,
    serverSlug: String?,
    target: SecondFactorStepUpWebTarget,
): String {
    val slug = serverSlug?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
    val path =
        when {
            slug != null -> "s/$slug/${target.webPath()}"
            target == SecondFactorStepUpWebTarget.Account -> target.webPath()
            else -> ""
        }
    return sharedSettingsWebUrl(webBaseUrl, path)
}

/** The server a step-up's web page opens in: the selected one, else the session's current one. */
@RaftCoreInternalApi fun StoredAuthSession?.secondFactorStepUpWebServerSlug(): String? {
    val session = this ?: return null
    return session.selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: session.servers.firstOrNull { it.id == session.currentServerId }?.slug?.takeIf { it.isNotBlank() }
}

/**
 * The [SecondFactorStepUpCoordinator]'s `webUrl`: a target's page on the environment's web URL read
 * at the time ([webBaseUrl]) and the session's server ([readSession]).
 */
@RaftCoreInternalApi fun secondFactorStepUpWebUrlResolver(
    webBaseUrl: () -> String?,
    readSession: suspend () -> StoredAuthSession?,
): suspend (SecondFactorStepUpWebTarget) -> String? = { target ->
    secondFactorStepUpWebUrl(webBaseUrl(), readSession().secondFactorStepUpWebServerSlug(), target)
}
