package build.raft.app.data

internal fun authSessionFixture(
    userId: String = "user-1",
    servers: List<StoredAuthServer> = authServerFixtures(),
    selectedServerId: String? = servers.firstOrNull()?.id,
): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = userId, email = "$userId@example.com"),
        accessToken = "access-$userId",
        refreshToken = "refresh-$userId",
        selectedServerId = selectedServerId,
        selectedServerSlug = servers.firstOrNull { it.id == selectedServerId }?.slug,
        servers = servers,
    )

internal fun authServerFixtures(): List<StoredAuthServer> =
    listOf(
        StoredAuthServer(id = "server-1", name = "One", slug = "one"),
        StoredAuthServer(id = "server-2", name = "Two", slug = "two"),
    )
