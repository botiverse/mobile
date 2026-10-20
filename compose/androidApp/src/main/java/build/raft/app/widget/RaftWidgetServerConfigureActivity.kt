package build.raft.app.widget

import build.raft.app.MainActivity
import build.raft.app.R
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RaftWidgetServerConfigureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedServerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        showLoading()
        scope.launch {
            val session = withContext(Dispatchers.IO) {
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(this@RaftWidgetServerConfigureActivity))
                    .readSession()
            }
            showServerPicker(session)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showLoading() {
        val root = baseColumn()
        root.gravity = Gravity.CENTER
        root.addView(ProgressBar(this))
        root.addView(label("Loading servers...", sizeSp = 16f, topDp = 16))
        setContentView(root)
    }

    private fun showServerPicker(session: StoredAuthSession?) {
        if (session == null || session.servers.isEmpty()) {
            showEmptyState()
            return
        }
        val configStore = RaftWidgetServerConfigStore(this)
        val currentSelection = configStore.readSelection(appWidgetId, session)
        selectedServerId = currentSelection?.serverId ?: session.currentServerId ?: session.servers.first().id

        val root = baseColumn()
        root.addView(label("Choose server", sizeSp = 24f, bold = true))
        root.addView(
            label(
                "This widget will refresh from the selected Raft server.",
                sizeSp = 14f,
                topDp = 8
            )
        )

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(20), 0, dp(20))
        }
        session.servers.forEachIndexed { index, server ->
            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = server.optionLabel()
                textSize = 16f
                tag = server.id
                isChecked = server.id == selectedServerId
                setPadding(0, dp(8), 0, dp(8))
            }
            radioGroup.addView(radioButton)
            if (index == 0 && selectedServerId.isNullOrBlank()) {
                selectedServerId = server.id
                radioButton.isChecked = true
            }
        }
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedServerId = group.findViewById<RadioButton>(checkedId)?.tag as? String
        }
        root.addView(radioGroup)

        root.addView(actionButton("Add widget") {
            val selected = session.servers.firstOrNull { it.id == selectedServerId } ?: session.servers.first()
            configStore.saveSelection(appWidgetId, selected)
            refreshConfiguredWidget()
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
            finish()
        })
        root.addView(actionButton("Open Raft", topDp = 10) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showEmptyState() {
        val root = baseColumn()
        root.gravity = Gravity.CENTER
        root.addView(label("Sign in first", sizeSp = 24f, bold = true))
        root.addView(label("Open Raft and sign in before adding a server-specific widget.", sizeSp = 14f, topDp = 8))
        root.addView(actionButton("Open Raft", topDp = 24) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        setContentView(root)
    }

    private fun refreshConfiguredWidget() {
        val providerClass = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)
            ?.provider
            ?.className
        when (providerClass) {
            ComponentName(this, SlockAgentStatusWidgetProvider::class.java).className ->
                SlockAgentStatusWidgetProvider.refreshWidget(this, appWidgetId)
            else ->
                SlockActivityWidgetProvider.refreshWidget(this, appWidgetId)
        }
    }

    private fun baseColumn(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

    private fun label(text: String, sizeSp: Float, bold: Boolean = false, topDp: Int = 0): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(getColor(android.R.color.black))
            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topDp)
            }
        }

    private fun actionButton(text: String, topDp: Int = 0, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(getColor(android.R.color.black))
            setBackgroundResource(R.drawable.raft_widget_header_background)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topDp)
            }
        }

    private fun StoredAuthServer.optionLabel(): String =
        name.ifBlank { slug ?: id } + (slug?.let { "\n/$it" } ?: "")

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
