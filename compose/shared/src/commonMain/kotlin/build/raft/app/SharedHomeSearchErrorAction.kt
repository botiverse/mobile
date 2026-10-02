package build.raft.app

import build.raft.app.data.SearchSort
import build.raft.app.data.SharedHomeSearchSavedIntent

/** A broad relevance query cannot recover by retrying the same request. */
internal fun sharedHomeSearchErrorAction(code: String, sort: SearchSort): SharedHomeSearchSavedIntent? =
    when {
        code != "QUERY_TOO_BROAD" -> SharedHomeSearchSavedIntent.RefreshSearch
        sort != SearchSort.Recent -> SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent)
        else -> null
    }
