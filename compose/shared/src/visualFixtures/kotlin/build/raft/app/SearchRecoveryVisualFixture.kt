package build.raft.app

import androidx.compose.runtime.*
import build.raft.app.base.BasePager
import build.raft.app.data.*
import build.raft.app.model.ServerId
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.foundation.layout.*
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page

/** Real search UI -> intent -> store -> repository; only HTTP is replaced. */
@Page("compose_search_recovery_visual", supportInLocal = true)
internal class SearchRecoveryVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockThemeProvider(brutalTheme) { SearchRecoveryVisualContent() } }
    }
}

@Composable
private fun SearchRecoveryVisualContent() {
    val scope = rememberCoroutineScope()
    var wireStatus by remember { mutableStateOf("Waiting for initial request") }
    val store = remember {
        val auth = InMemorySlockAuthSessionStore(homeSearchVisualSession)
        val network = SearchRecoveryVisualNetwork { wireStatus = it }
        SharedHomeSearchSavedStore(
            authStore = auth, savedRepository = RealSavedRepository(auth, network),
            searchRepository = RealSearchRepository(auth, network), scope = scope,
            timeRangeBoundsProvider = { SearchIsoBounds("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z") },
        )
    }
    DisposableEffect(store) { onDispose { store.clear() } }
    LaunchedEffect(store) {
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchChannel("channel-1"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("user-1"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("agent-2"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Mentioned))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Humans))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchTimeRange(SearchTimeRange.Today))
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
    }
    val state by store.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(wireStatus, modifier = Modifier.padding(16.dp))
        SharedHomeSearchPanel(
            state = state.search, homeState = SharedHomeState(),
            onIntent = store::dispatch, onOpenItem = {}, onOpenEntity = {},
            bottomContentPadding = 0.dp, modifier = Modifier.weight(1f),
        )
    }
}

private class SearchRecoveryVisualNetwork(val report: (String) -> Unit) : SlockNetworkClient {
    private var initial: List<Pair<String, String>>? = null
    override suspend fun request(
        priority: DataRequestPriority, path: String, method: String,
        query: List<Pair<String, String>>, serverIdOverride: ServerId?, bodyText: String?
    ): HomeNetworkResponse {
        check(path == "messages/search")
        if (query.any { it == "sort" to "recent" }) {
            val preserved = query.filterNot { it.first == "sort" } == initial
            val firstPage = query.any { it == "offset" to "0" }
            report(if (preserved && firstPage) "PASS: recent / first page / all filters preserved" else "FAIL: request changed")
            return HomeNetworkResponse(200, "")
        }
        initial = query.toList()
        report("HTTP 422 QUERY_TOO_BROAD — tap recovery below")
        return HomeNetworkResponse(422, null, """{"error":"Search query is too broad.","code":"QUERY_TOO_BROAD"}""")
    }
}
