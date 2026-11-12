---
type: changed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 4627
rollout: full
---

## zh-CN

计算机页的工具栏改成和网页版一样：搜索框占满一行，然后是「状态」和「运行时」筛选（有筛选时末尾有「清除筛选」），接着是「分组」、「排序」、卡片 / 列表切换，最后是「添加计算机」。卡片 / 列表切换和「添加计算机」从页面顶部移到了工具栏末尾，宽屏的页面顶部只保留「计算机」标题和数量（几台计算机、几台在线、几个 Agent）。计算机列表还在加载、还没有计算机或加载失败时，工具栏也会一直显示；还没有计算机时，「添加计算机」在工具栏末尾，提示里不再单独放添加按钮。屏幕变窄时，工具栏会一步步收起：先把「分组」、「排序」和视图切换缩成图标，再把筛选缩成图标，然后把筛选移到第二行，「添加计算机」变成「+」，「分组」、「排序」和视图切换收进「更多」（⋯）菜单，最后搜索框变成搜索按钮。搜索按钮展开后，可以用系统返回键先清空文字、再收起搜索。

## en

The toolbar on Computers now matches the web: the search fills the line, then the Status and Runtime filters (with Clear all at their end while a filter is set), Group, Sort, the Cards / List switch, and Add Computer last. The Cards / List switch and Add Computer moved from the top of the page to the end of the toolbar; on a wide screen the top of the page keeps just the Computers title and the counts (computers, online, agents). The toolbar now stays on screen while the list loads, when there are no computers yet and when the list fails to load; with no computers yet, Add Computer is at the toolbar's end and the message no longer has its own add button. On a narrower screen the toolbar folds step by step: Group, Sort and the view switch show only their icons, then the filters do, then the filters move to a second line, Add Computer becomes +, Group, Sort and the view switch move into More (⋯), and last the search becomes a search button. Once the search is opened from that button, the system Back button clears the text first and then closes it.

## ja

コンピューターページのツールバーがウェブ版と同じになりました。検索欄が 1 行いっぱいに広がり、続いて「状態」と「ランタイム」のフィルター（フィルターを設定中は末尾に「すべてクリア」）、「グループ」、「並べ替え」、カード / リストの切り替え、最後に「コンピューターを追加」が並びます。カード / リストの切り替えと「コンピューターを追加」はページ上部からツールバーの末尾に移り、ワイド画面のページ上部には「コンピューター」の見出しと台数（コンピューター数・オンライン数・エージェント数）だけが残ります。一覧の読み込み中、コンピューターがまだないとき、読み込みに失敗したときもツールバーは表示されたままです。コンピューターがまだないときは「コンピューターを追加」がツールバーの末尾にあり、メッセージには追加ボタンを別に置かなくなりました。画面が狭くなると、ツールバーは段階的にたたまれます。まず「グループ」「並べ替え」と表示の切り替えがアイコンだけになり、次にフィルターもアイコンだけになり、フィルターが 2 行目に移り、「コンピューターを追加」が「+」になり、「グループ」「並べ替え」と表示の切り替えが「その他」（⋯）に入り、最後に検索欄が検索ボタンになります。検索ボタンから開いた検索は、システムの戻るボタンでまず文字を消し、もう一度押すと閉じます。
