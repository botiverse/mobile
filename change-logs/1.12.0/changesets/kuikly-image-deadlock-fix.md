---
type: fixed
area: attachments
platforms:
  - android
pull_request: 2528
rollout: full
---

## zh-CN

修复在频道滚动后打开本地图片预览导致永久卡住转圈、点击完成按钮置灰无响应的线程池死锁问题：Kuikly 图片适配器底层消除在固定 4 线程池内部嵌套提交子任务并同步等待 `CountDownLatch.await()` 的自锁设计，将网络图片字节下载移至 `Dispatchers.IO` 异步协程池，位图解码与局部文件读取在空闲工作线程中执行，彻底杜绝线程饥饿与死锁。

## en

Fixed a thread pool starvation deadlock where opening a local image preview after scrolling channels caused an infinite spinner and disabled the "Done" button: eliminated nested sub-task submission and blocking `CountDownLatch.await()` on the fixed 4-thread Kuikly background pool; moved network byte downloads to an asynchronous `Dispatchers.IO` scope while keeping bitmap decoding and local file reads on free worker threads.
