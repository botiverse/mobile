package build.raft.app

// Fixed identity for single-owner thread cache tests. Owner-less and blank-environment defaults
// were deleted (task #1121): every SharedThreadDataSource names its cache environment, and every
// payload-store / freshness-ledger call names its owner scope, environment and principal.
internal const val TestThreadEnvironmentId = "https://prod.example.test"
internal const val TestThreadPrincipalId = "user-1"
internal const val TestThreadOwnerScopeId =
    "https://prod.example.test\u001fuser-1\u001fserver-1\u001f1\u001f1"
