---
type: fixed
area: attachments
platforms:
  - android
pull_request: 1691
rollout: full
---

## zh-CN

Android 附件预览现在会在打开页面时检查当前附件是否已完整保存到系统“下载/Raft”目录：已下载的文件可直接打开，未下载的文件保留下载入口。若文件被系统或其他应用删除，页面会在打开前重新核验并回到可重新下载的状态，不会误用同名文件或显示失效的“打开”操作。

## en

Android attachment previews now check on entry whether the exact attachment is fully present in the system Downloads/Raft folder. Existing downloads open directly, while missing downloads retain the Download action. If the file is later removed by the system or another app, FilePreview revalidates it before opening and returns to an actionable re-download state instead of adopting a same-named file or leaving a stale Open action.
