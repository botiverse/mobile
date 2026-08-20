package build.raft.app

import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.StoredQaBootstrapMetadata
import kotlin.test.Test
import kotlin.test.assertTrue

class InviteAcceptGateTest {
    @Test
    fun pendingInviteUsesEveryCanonicalPrerequisiteBeforeAccept() {
        val unverified =
            session(
                emailVerified = false,
                profileSetupCompletedAt = "2026-08-20T00:00:00Z",
                selectedServerId = "server-1",
            )
        val bootstrapRecovery =
            session(
                emailVerified = true,
                profileSetupCompletedAt = "2026-08-20T00:00:00Z",
                selectedServerId = "server-1",
            ).copy(
                qaBootstrap =
                    StoredQaBootstrapMetadata(
                        ticketDigest = "sha256:ticket",
                        runCaseId = "run-case",
                        attempt = 1,
                        ciJobRef = "gha:job",
                        principalId = "user-1",
                        serverId = "server-1",
                        sessionGeneration = "generation-1",
                        targetRoute = "compose_slock_app_shell",
                        applicationId = "build.raft.app.alpha",
                        signingCertSha256 = "sha256:cert",
                        deviceBootGeneration = "sha256:boot",
                    )
            )
        val pendingProfile =
            session(
                emailVerified = true,
                profileSetupCompletedAt = null,
                selectedServerId = "server-1",
            )
        val missingServer =
            session(
                emailVerified = true,
                profileSetupCompletedAt = "2026-08-20T00:00:00Z",
                selectedServerId = null,
            )
        val ready =
            session(
                emailVerified = true,
                profileSetupCompletedAt = "2026-08-20T00:00:00Z",
                selectedServerId = "server-1",
            )

        assertTrue(inviteAcceptGateState(bootstrapRecovery) is SharedAuthGateState.BootstrapRecoveryRequired)
        assertTrue(inviteAcceptGateState(unverified) is SharedAuthGateState.EmailVerificationRequired)
        assertTrue(inviteAcceptGateState(pendingProfile) is SharedAuthGateState.ProfileSetupRequired)
        assertTrue(inviteAcceptGateState(missingServer) is SharedAuthGateState.ServerRequired)
        assertTrue(inviteAcceptGateState(ready) is SharedAuthGateState.Ready)
    }

    private fun session(
        emailVerified: Boolean,
        profileSetupCompletedAt: String?,
        selectedServerId: String?,
    ): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "user@raft.build",
                    emailVerified = emailVerified,
                    profileSetupCompletedAt = profileSetupCompletedAt,
                ),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerId?.let { "raft" },
            servers =
                selectedServerId?.let {
                    listOf(StoredAuthServer(id = it, name = "Raft", slug = "raft"))
                }.orEmpty(),
        )
}
