package build.raft.app.compat.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

@OptIn(ExperimentalTextApi::class)
actual fun KuiklyFont(name: String, weight: FontWeight, style: FontStyle): Font = SystemFont(name, weight, style)
