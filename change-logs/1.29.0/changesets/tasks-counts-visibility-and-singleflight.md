---
change_type: fix
platforms: [android, ios]
pull_request: 5371
---

- zh-Hans: 优化任务页计数刷新：切离任务页时暂停后台拉取、返回时补拉，并在前台保持 10 秒最小刷新间隔与并发单飞去重。
- zh-Hant: 最佳化任務頁計數重新整理：切離任務頁時暫停背景抓取、返回時補拉，並於前景維持 10 秒最小重新整理間隔與併發單飛去重。
- en: Pause Tasks tab count refreshes when off-screen, refetch on return, enforce a 10-second minimum refresh interval, and coalesce concurrent count requests.
- ja: タスクタブが非表示のときは件数のバックグラウンド更新を一時停止し、戻ったときに再取得するとともに、最小10秒間隔と同時リクエストの重複排除を適用しました。
- ko: 작업 탭이 화면 밖에 있을 때 개수 백그라운드 새로고침을 일시 중지하고 복귀 시 다시 불러오며, 최소 10초 간격 및 동시 요청 중복 제거를 적용했습니다.
- de: Pausiert Hintergrund-Aktualisierungen der Aufgabenzähler außerhalb des Aufgaben-Tabs, lädt bei Rückkehr neu, erzwingt ein 10-Sekunden-Mindestintervall und bündelt parallele Anfragen.
- fr: Met en pause l'actualisation des compteurs hors de l'onglet Tâches, recharge au retour, applique un intervalle minimal de 10 secondes et regroupe les requêtes simultanées.
- es: Pausa la actualización de contadores fuera de la pestaña Tareas, vuelve a cargar al regresar, aplica un intervalo mínimo de 10 segundos y agrupa solicitudes concurrentes.
