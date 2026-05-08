# Android Members Workspace File Preview Contract

Task: #167 Android Members Workspace: Research file preview API contracts for images and text

## Web Source

Inspected:

- `/Users/artin/Downloads/index-EpAFmlk5.js`
- `https://app.slock.ai/assets/AgentDetailPanel-VQPE7dIX.js`
- `https://app.slock.ai/assets/MachineDetailPanel-7hX6hcQ-.js`

Relevant Web route is Agent detail -> Workspace tab, not Settings Computer detail. Computer detail only scans workspace directories through `/servers/{serverId}/machines/{machineId}/workspaces`.

## Confirmed Endpoints

All requests use the existing authenticated API client. Web attaches `Authorization` and selected `X-Server-Id` through interceptors; Android UI must not pass or read token/header/raw response.

### List root workspace files

`GET /agents/{agentId}/workspace-files`

Web expects response:

```json
{
  "files": [
    {
      "name": "MEMORY.md",
      "path": "MEMORY.md",
      "isDirectory": false
    }
  ]
}
```

Observed fields used by Web:

- `files`: array
- per node:
  - `name`
  - `path`
  - `isDirectory`

### List directory children

`GET /agents/{agentId}/workspace-files?dirPath={path}`

Same response shape as root listing: `{ files: [...] }`.

Trigger:

- User expands a directory.
- Web caches children by directory path for the current agent.
- Refresh clears cached directory children, reloads root, and reloads any expanded directory.

### Read file content / preview

`GET /agents/{agentId}/workspace-files/read?path={path}`

Observed Web fields:

```json
{
  "path": "MEMORY.md",
  "content": "# Notes",
  "binary": false,
  "size": 1234,
  "modifiedAt": "2026-05-07T00:00:00.000Z"
}
```

Fields consumed by Web:

- `path`
- `content`
- `binary`
- `size`
- `modifiedAt`

Rendering behavior:

- If `binary == true`: Web shows "Binary file - cannot display".
- If path ends with `.md` and mode is `preview`: Web renders Markdown with `react-markdown` + GFM.
- Otherwise: Web shows raw text in a preformatted text block.
- Markdown preview supports code/pre, links, lists, tables, blockquote, headings, horizontal rule, and inline images.

## Not Confirmed

No separate image bytes/thumbnail endpoint was found in the downloaded Web bundle for workspace files.

Current Web workspace preview treats binary files as unsupported, even for image paths. For Android #166, image preview should therefore start with:

- sample/mock image preview support, or
- repository state that can represent image preview once an API is confirmed,
- but should not invent a real image file endpoint yet.

If backend later exposes binary/image content, likely options to confirm are:

- `GET /agents/{agentId}/workspace-files/read?path={path}` returning a signed/download URL for binary/image files
- `GET /agents/{agentId}/workspace-files/download?path={path}`
- direct attachment/file service URL

Do not implement any of those as real API calls until observed or confirmed.

## Android Repository Boundary Recommendation

Add a dedicated repository instead of letting `MembersScreen` call network APIs.

```kotlin
interface WorkspaceFileRepository {
    suspend fun getRoot(agentId: AgentId): DataResult<WorkspaceFileTreeSnapshot>
    suspend fun getChildren(agentId: AgentId, dirPath: String): DataResult<WorkspaceFileChildrenSnapshot>
    suspend fun readFile(agentId: AgentId, path: String): DataResult<WorkspaceFilePreviewSnapshot>
}
```

Suggested snapshots:

```kotlin
data class WorkspaceFileNodeSnapshot(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val kind: WorkspaceFileKind,
    val sizeBytes: Long? = null,
    val modifiedAt: String? = null
)

enum class WorkspaceFileKind {
    Folder,
    Markdown,
    Text,
    Image,
    Unsupported
}

sealed interface WorkspaceFilePreviewSnapshot {
    data class Text(
        val path: String,
        val fileName: String,
        val content: String,
        val markdown: Boolean,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : WorkspaceFilePreviewSnapshot

    data class Image(
        val path: String,
        val fileName: String,
        val imageUrl: String? = null,
        val sampleAssetKey: String? = null,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : WorkspaceFilePreviewSnapshot

    data class Unsupported(
        val path: String,
        val fileName: String,
        val reason: String,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : WorkspaceFilePreviewSnapshot
}
```

Mapping rules:

- `isDirectory == true` -> `Folder`.
- extension `.md` / `.markdown` -> `Markdown`.
- common text extensions (`.txt`, `.json`, `.kt`, `.java`, `.xml`, `.md`, `.yaml`, `.yml`, `.toml`, `.gradle`) -> `Text` / `Markdown`.
- common image extensions (`.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`) -> `Image` kind in the tree, but real preview should remain mock/sample until API confirms binary image delivery.
- `binary == true` without image URL -> `Unsupported`.

Error/state mapping:

- no auth/session -> `DataResult.NoPermission("auth_required")`
- no selected server -> `DataResult.NoPermission("server_required")`
- 403 -> `DataResult.NoPermission("workspace_forbidden")`
- 404 -> `DataResult.Empty("workspace_not_found")` for tree, or `DataResult.Error("workspace_file_not_found")` for selected file
- parse/shape mismatch -> `DataResult.Error("workspace_files_contract_mismatch")`
- empty `files` -> content state with empty tree, not a full-screen failure

Safe diagnostics:

- Log only source (`workspace_files_root`, `workspace_files_children`, `workspace_file_read`), `agentId`, safe `path`, HTTP status, exception class, and root keys/field path.
- Do not log token/header/raw response/body/file content.

## UI Handoff for #166

UI can proceed against mock/sample repository output:

- tree loading / empty / error / noPermission
- directory-level loading while expanding
- selected file preserved when returning to tree
- file detail header: name, path, type/kind, size, modified time
- Markdown preview using the existing message Markdown renderer or equivalent
- text preview as raw/preformatted content
- image preview as sample asset or `imageUrl` only if repository provides one
- unsupported state for binary/no-preview files
