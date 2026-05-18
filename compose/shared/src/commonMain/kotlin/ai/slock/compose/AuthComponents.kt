package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class AuthProvider {
    Google,
    GitHub
}

internal enum class AuthButtonTone {
    Pink,
    Cyan,
    White,
    Black,
    Disabled
}

@Composable
internal fun AuthScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        AuthTopBar(statusBarHeight)
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 36.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 314.dp)
                    .padding(top = 136.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

@Composable
private fun AuthTopBar(statusBarHeight: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((statusBarHeight + 52).dp)
            .background(SlockColor.BrandYellow)
    ) {
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack).align(Alignment.BottomCenter))
        Row(
            modifier = Modifier
                .padding(top = statusBarHeight.dp)
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuthSmallBrand()
        }
    }
}

@Composable
private fun AuthSmallBrand() {
    Image(
        painter = rememberAsyncImagePainter(SlockLockupLightDataUri),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.width(92.dp).height(20.dp)
    )
}

@Composable
internal fun AuthBrandBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        AuthLogoBadge("SLOCK")
        Text(
            text = "WHERE HUMANS AND AI AGENTS\nCOLLABORATE",
            style = SlockType.SectionLabel.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.4.sp,
                color = SlockColor.TextBlack.copy(alpha = 0.52f)
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AuthCenterMark(modifier: Modifier = Modifier) {
    Image(
        painter = rememberAsyncImagePainter(SlockIconDataUri),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.width(34.dp).height(31.dp)
    )
}

@Composable
internal fun AuthLogoBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(166.dp).height(64.dp).padding(end = 4.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 4.dp, y = 4.dp).background(SlockColor.ShadowBlack))
        Box(
            modifier = Modifier.fillMaxSize().background(SlockColor.TextBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = SlockType.PageTitle.copy(
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    color = SlockColor.BrandYellow
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun AuthFormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
        content = content
    )
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    required: Boolean = false,
    optional: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    forceFocused: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        AuthFieldLabel(label = label, required = required, optional = optional)
        val focused = enabled && (isFocused || forceFocused)
        val shadowOffset = if (focused) 4.dp else 2.dp
        val borderColor = if (isError) SlockColor.AccentRed else SlockColor.BorderBlack
        Box(modifier = Modifier.fillMaxWidth().height(40.dp).padding(end = shadowOffset, bottom = shadowOffset)) {
            Box(Modifier.fillMaxSize().offset(x = shadowOffset, y = shadowOffset).background(SlockColor.ShadowBlack))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.EmptyStateGray)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(
                        text = placeholder,
                        style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextField(
                    value = value,
                    placeholder = "",
                    autoFocus = false,
                    onFocus = { isFocused = true },
                    onBlur = { isFocused = false },
                    onValueChange = onValueChange,
                    textStyle = SlockType.Input.copy(color = SlockColor.TextBlack),
                    cursorBrush = SolidColor(SlockColor.TextBlack),
                    maxLines = 1,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (isError && !errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
    }
}

@Composable
internal fun AuthFieldLabel(label: String, required: Boolean = false, optional: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.AccentPink),
                color = SlockColor.AccentPink
            )
        } else if (optional) {
            Text(
                text = "optional",
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun AuthActionButton(
    text: String,
    onClick: () -> Unit,
    tone: AuthButtonTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    preserveDisabledVisual: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null
) {
    val background = when (tone) {
        AuthButtonTone.Pink -> SlockColor.AccentPink
        AuthButtonTone.Cyan -> SlockColor.AccentCyan
        AuthButtonTone.White -> SlockColor.SurfaceWhite
        AuthButtonTone.Black -> SlockColor.TextBlack
        AuthButtonTone.Disabled -> SlockColor.EmptyStateGray
    }
    val contentColor = if (tone == AuthButtonTone.Black) SlockColor.SurfaceWhite else SlockColor.TextBlack
    val buttonBackground = if (enabled || preserveDisabledVisual) background else SlockColor.EmptyStateGray
    Box(modifier = modifier.fillMaxWidth().height(36.dp).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(buttonBackground)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled && !loading, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingContent != null) {
                    Box(Modifier.padding(end = 8.dp), contentAlignment = Alignment.Center) {
                        leadingContent()
                    }
                }
                Text(
                    text = if (loading && !text.endsWith("...")) "$text..." else text,
                    style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = contentColor),
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun AuthDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Text(
            text = "OR",
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Box(Modifier.weight(1f).height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
internal fun AuthTextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = SlockType.Body.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = SlockColor.TextMutedGray,
            textDecoration = TextDecoration.Underline
        ),
        color = SlockColor.TextMutedGray,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun AuthInlineAction(leading: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = leading,
            style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Spacer(Modifier.width(SlockSpacing.Xs))
        Text(
            text = action,
            style = SlockType.Body.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = SlockColor.AccentPink,
                textDecoration = TextDecoration.Underline
            ),
            color = SlockColor.AccentPink,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
internal fun AuthError(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(
            text = message,
            style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = SlockColor.AccentRed),
            color = SlockColor.AccentRed
        )
    }
}

@Composable
internal fun AuthSuccessNotice(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.AccentCyan)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = title,
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = detail,
            style = SlockType.Body.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
internal fun AuthProviderIcon(provider: AuthProvider) {
    val source =
        when (provider) {
            AuthProvider.Google -> GoogleIconDataUri
            AuthProvider.GitHub -> GitHubIconDataUri
        }
    Image(
        painter = rememberAsyncImagePainter(source),
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}

private const val SlockLockupLightDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20width=%22253%22%20height=%2255%22%20viewBox=%220%200%201164%20253%22%20fill=%" +
    "22none%22%20xmlns=%22http%3A//www.w3.org/2000/svg%22%3E%0A%3Cg%20clip-path=%22url%28%23clip0_134_862%29%22%3E%" +
    "0A%3Cpath%20d=%22M426.04%2018.6562C450.39%2018.6563%20468.98%2023.5464%20482.8%2033.6963C483.919%2034.5062%204" +
    "84.95%2035.4063%20485.92%2036.4062C488.6%2038.8262%20490.95%2041.2764%20493.04%2043.8164C494.75%2039.2765%2049" +
    "6.68%2035.6361%20499.03%2032.5762C504.19%2025.8764%20512.24%2022.1368%20520.679%2022.5566C521.639%2022.6166%20" +
    "522.96%2022.6162%20524.67%2022.6162H529.179L545.38%2022.4258H545.7C576.85%2022.3258%20585.81%2023.9364%20598.6" +
    "6%2037.4062C600.66%2039.4963%20608.71%2047.3558%20611.87%2052.6758C615.22%2058.3757%20616.28%2065.1461%20614.7" +
    "7%2071.5859C616.03%2069.6562%20617.32%2067.8163%20618.67%2066.0166C618.8%2065.8167%20618.96%2065.6258%20619.09" +
    "%2065.4658C631.109%2050.296%20645.99%2038.6063%20663.45%2030.7764C680.42%2023.0864%20697.4%2019.2159%20716.02%" +
    "2019.2158C734.64%2019.2159%20751.33%2023.2064%20765.599%2031.0361C774.329%2035.8061%20794.779%2056.1662%20798." +
    "099%2060.2266C809.569%2046.8966%20823.39%2036.8761%20839.27%2030.4961C855.64%2023.9263%20872.87%2020.6065%2089" +
    "0.429%2020.6064C917.649%2020.6064%20938.4%2027.6867%20952.28%2041.7666C953.79%2037.6766%20956.14%2033.496%2095" +
    "9.88%2029.916C964.42%2025.6661%20970.32%2023.1861%20976.57%2022.9561C978.73%2022.8561%20982.4%2022.8262%20987." +
    "46%2022.8262C992.52%2022.8262%20996.9%2022.8961%201000.57%2022.9561L1013.07%2023.1162C1016.13%2022.9262%201020" +
    "%2022.8262%201024.8%2022.8262C1028.79%2022.8262%201039.52%2022.8267%201048.35%2029.3965C1052.79%2025.7565%2010" +
    "58.4%2023.726%201064.2%2023.626C1073.47%2023.536%201091.45%2023.4367%201109.07%2023.8867C1117.38%2024.1067%201" +
    "123.7%2024.2363%201127.82%2024.2363C1137.58%2024.2367%201157.59%2046.9451%201160.42%2052.7158V52.6562L1160.43%" +
    "2052.626C1164.72%2061.356%201163.72%2071.7964%201157.79%2079.5264C1138.88%20104.206%201123.9%20123.526%201113." +
    "08%20137.126C1114.94%20141.766%201117.3%20147.916%201120.32%20156.326C1125.87%20171.596%201129.89%20182.457%20" +
    "1132.4%20188.637L1133.89%20192.406C1133.95%20192.476%201133.98%20192.566%201134.02%20192.666C1136.88%20199.976" +
    "%201139.56%20206.907%201137.78%20215.347C1136.66%20220.627%201134.05%20225.557%201130.25%20229.517C1125.41%202" +
    "34.506%201118.78%20237.376%201111.79%20237.376H1110.27C1107.05%20237.246%201104.45%20237.187%201102.45%20237.1" +
    "87C1100.45%20237.187%201097.81%20237.246%201094.55%20237.376H1050.03C1043.62%20237.376%201037.69%20234.956%201" +
    "033.12%20230.966C1024.13%20238.406%201012.37%20238.406%201006.41%20238.406C1005.96%20238.406%201005.58%20238.3" +
    "77%201005.19%20238.347H999.13L962.66%20238.536C951.65%20238.536%20942.05%20232.866%20937.02%20223.786C936.57%2" +
    "0223.046%20936.21%20222.336%20935.86%20221.597C935.8%20221.537%20935.67%20221.506%20935.57%20221.437C930.03%20" +
    "225.276%20924.07%20228.586%20917.66%20231.296C901.56%20238.186%20884.51%20241.666%20866.99%20241.666C828.08%20" +
    "241.666%20799.149%20218.346%20785.3%20200.526C783.85%20202.206%20782.4%20203.816%20780.92%20205.326L775.349%20" +
    "211.126C775.019%20211.416%20774.7%20211.737%20774.38%20212.057C764.14%20221.596%20751.12%20228.936%20735.79%20" +
    "233.866C721.59%20238.376%20707.639%20240.766%20694.3%20240.956H693.98C669.78%20240.956%20650.1%20235.256%20635" +
    ".38%20224.046C635.02%20223.786%20634.63%20223.496%20634.28%20223.206C626.9%20236.936%20613.469%20237.356%20588" +
    ".05%20237.996C571.84%20238.406%20547.459%20238.637%20515.63%20238.637C512.63%20238.637%20507.639%20238.576%205" +
    "00.71%20238.446C490.37%20238.256%20481.71%20233.036%20473.78%20220.536C463.89%20227.016%20451.489%20232.526%20" +
    "435.8%20236.196C425.72%20238.586%20414.96%20240.326%20403.91%20241.356C403.14%20241.456%20402.329%20241.486%20" +
    "401.559%20241.486C387.06%20241.486%20372.919%20239.326%20359.55%20235.006C343.349%20229.845%20312.13%20196.446" +
    "%20309.46%20188.616C306.69%20182.496%20305.11%20176.436%20304.69%20170.576C304.37%20165.716%20304.589%20159.94" +
    "6%20305.429%20152.477C306.259%20144.647%20310.71%20137.656%20317.41%20133.536C319.53%20132.276%20321.82%20131." +
    "277%20324.139%20130.637C319.089%20121.577%20315.74%20108.756%20319.41%2091.5566C321.7%2080.6367%20325.92%2070." +
    "6158%20331.91%2061.7559C337.77%2053.1261%20344.73%2045.8462%20352.59%2040.2363C372.63%2025.9064%20397.3%2018.6" +
    "563%20426.04%2018.6562ZM419.03%2044.2861C384.16%2043.3961%20341.129%2064.9163%20345.559%20102.236C348.409%2012" +
    "6.346%20374.019%20134.866%20389.019%20138.246C404.019%20141.626%20415.83%20146.126%20414.24%20156.166C411.83%2" +
    "0166.546%20394.259%20169.556%20388.229%20164.086C384.28%20160.506%20384.139%20154.616%20384.139%20154.616C384." +
    "006%20154.616%20331.112%20154.676%20330.91%20154.616C330.26%20160.376%20330.62%20165.696%20331.42%20168.786H33" +
    "1.389C336.829%20189.236%20357.42%20199.066%20379.13%20201.416C392.299%20202.836%20405.869%20201.446%20416.689%" +
    "20198.066C447.389%20190.786%20465.45%20174.296%20469.59%20151.646C470.92%20144.346%20470.869%20131.496%20465.5" +
    "09%20123.416C455.64%20108.556%20434.03%20104.306%20427.74%20103.036C399.64%2097.3861%20399.38%2092.5764%20400." +
    "03%2088.8164H400.04C400.79%2084.4464%20407.56%2080.0264%20416.28%2080.1064C421.75%2080.1565%20432.61%2082.3565" +
    "%20434%2090.0264H483.36C483.36%2071.8864%20468.89%2045.5463%20419.03%2044.2861ZM890.509%2046.2959C876.11%2046." +
    "296%20862.259%2048.9658%20848.889%2054.3457C833.88%2060.3657%20821.379%2070.5764%20811.389%2084.9463C808.21%20" +
    "89.4562%20805.15%2095.3862%20802.15%20102.636C800.21%20107.306%20798.349%20112.456%20796.509%20118.226C794.29%" +
    "20128.626%20793.26%20138.096%20793.32%20146.636H793.29C793.39%20160.035%20796.25%20171.186%20801.889%20180.105" +
    "C811.169%20194.665%20828.15%20201.946%20852.79%20201.946C889.74%20201.946%20911.659%20186.506%20924.849%20168." +
    "216C933.3%20156.488%20936.343%20142.216%20936.349%20142.186L881.599%20141.996C878.119%20153.546%20871.57%20163" +
    ".946%20860.65%20161.996C850.72%20160.226%20846.16%20147.556%20854.71%20118.906C859.33%20103.416%20867.48%2084." +
    "6166%20880.55%2084.6162C891.44%2084.6162%20889.65%20105.656%20889.65%20105.656H942.88L942.889%20105.666C942.88" +
    "9%20105.666%20946.52%2093.4855%20944.05%2078.8955C943.249%2074.1642%20935.948%2046.2959%20890.509%2046.2959ZM7" +
    "16.16%2044.8965C701.82%2044.8965%20687.809%2047.9865%20674.09%2054.1465C660.4%2060.3665%20631.19%2077.5866%206" +
    "21.58%20120.416C616.78%20141.816%20617.86%20173.636%20636.819%20189.576C646.619%20197.816%20661.369%20201.236%" +
    "20679.859%20201.236C690.749%20201.076%20702.059%20199.107%20713.849%20195.347C725.669%20191.547%20735.34%20186" +
    ".197%20742.84%20179.177L748.54%20173.276C756.59%20165.226%20762.999%20154.756%20767.899%20141.896C770.409%2013" +
    "5.296%20772.54%20128.136%20774.21%20120.246C775.95%20111.906%20776.76%20104.397%20776.66%2097.6367C776.66%2095" +
    ".9268%20776.629%2094.2567%20776.469%2092.6768V92.666C775.759%2084.7057%20771.189%2064.2661%20753.399%2053.5264" +
    "C743.169%2047.3466%20730.499%2044.8965%20716.16%2044.8965ZM943.66%20198.816H1002.7C1002.7%20198.816%201015.98%" +
    "20142.127%201017.62%20136.937C1023.71%20158.746%201029.76%20180.166%201035.79%20198.816C1035.96%20198.818%2010" +
    "98.35%20199.495%201098.48%20198.816C1098.51%20198.786%201069.19%20119.086%201069.19%20119.086H1069.2L1123.23%2" +
    "048.5664H1062.84L1024.33%20107.326H1021.04C1025.1%2088.1984%201033.5%2048.6175%201033.51%2048.5664H977.489L943" +
    ".66%20198.816ZM487.019%20198.726L487.05%20198.756H597.87L606.309%20157.036L551.609%20157.396L575.67%2048.0957H" +
    "519.359L487.019%20198.726ZM707.099%2081.2363C722.379%2081.2363%20725.199%20100.997%20718.479%20124.847C708.539" +
    "%20160.086%20697.319%20167.116%20684.769%20166.246C672.22%20165.376%20668.51%20148.776%20677.59%20119.437C686." +
    "689%2090.0069%20697.409%2081.2366%20707.099%2081.2363Z%22%20fill=%22black%22/%3E%0A%3Cpath%20d=%22M211.83%200." +
    "436221C219.54%20-0.783748%20227.69%200.466129%20233.91%205.3161C238.3%208.73627%20249.16%2018.0962%20252.21%20" +
    "20.5661C258.34%2025.536%20262.979%2031.8459%20264.349%2039.6257L273.96%2094.1559H273.95C276.75%20110.076%20266" +
    ".079%20125.386%20250.189%20128.286L247.08%20128.866C247.69%20130.536%20248.2%20132.276%20248.51%20134.076L257." +
    "929%20187.566C260.769%20203.576%20250.029%20218.886%20234.019%20221.726L62.4099%20251.976C49.2399%20254.296%20" +
    "44.0596%20247.736%2030.8796%20235.996C19.5299%20225.836%2011.7302%20220.566%209.53002%20210.346L0.959706%20161" +
    ".736C-0.840294%20151.396%20-0.44045%20145.475%205.04955%20137.425C10.1096%20129.986%2018.4103%20126.206%2028.9" +
    "001%20124.346C28.3501%20122.766%2027.8693%20121.146%2027.5593%20119.406L20.0593%2076.8356C18.0493%2065.4559%20" +
    "15.5198%2054.326%2024.0593%2042.7663C31.4393%2032.7963%2041.0097%2030.5656%2052.9997%2028.7956L211.83%200.4362" +
    "21ZM94.4099%20174.725C92.2099%20176.735%2087.1797%20179.395%2081.9997%20178.175C77.4897%20177.105%2074.2192%20" +
    "173.175%2073.3992%20168.595L68.7292%20142.095L33.2898%20148.345C27.9798%20149.295%2024.4191%20154.356%2025.359" +
    "1%20159.656L33.1697%20203.906C34.1199%20209.216%2039.1795%20212.785%2044.4792%20211.836V211.866L44.49%20211.87" +
    "6L206.889%20183.235C212.199%20182.285%20215.759%20177.226%20214.819%20171.916L207.01%20127.635C206.059%20122.3" +
    "25%20200.999%20118.756%20195.689%20119.706C190.379%20120.656%20184.369%20121.715%20181.359%20122.235C176.599%2" +
    "0123.085%20168.549%20120.135%20167.179%20112.355L164.679%2098.1755L94.4099%20174.725ZM222.81%2033.2556C221.86%" +
    "2027.9158%20216.8%2024.3859%20211.46%2025.3259L50.6804%2053.9558C45.3704%2054.9058%2041.84%2059.9663%2042.78%2" +
    "065.2663L50.7703%20110.556V110.596C51.7204%20115.906%2056.78%20119.476%2062.0798%20118.526L76.4802%20115.996C8" +
    "3.13%20114.836%2089.4402%20119.256%2090.6003%20125.876L93.1003%20140.056L163.37%2063.5056C166.51%2060.0857%201" +
    "71.33%2058.7464%20175.78%2060.0563C180.23%2061.3664%20183.59%2065.0865%20184.38%2069.6364L189.05%2096.0759L222" +
    ".93%2089.9157C228.21%2088.9355%20231.739%2083.906%20230.8%2078.6061L222.81%2033.2556Z%22%20fill=%22black%22/%3" +
    "E%0A%3C/g%3E%0A%3Cdefs%3E%0A%3CclipPath%20id=%22clip0_134_862%22%3E%0A%3Crect%20width=%221163.06%22%20height=%" +
    "22252.439%22%20fill=%22white%22/%3E%0A%3C/clipPath%3E%0A%3C/defs%3E%0A%3C/svg%3E"

private const val SlockIconDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20width=%22274%22%20height=%22253%22%20viewBox=%220%200%20274%20253%22%20fill=%" +
    "22none%22%20xmlns=%22http%3A//www.w3.org/2000/svg%22%3E%0A%3Cpath%20d=%22M211.83%200.436221C219.54%20-0.783748" +
    "%20227.69%200.466129%20233.91%205.3161C238.3%208.73627%20249.16%2018.0962%20252.21%2020.5661C258.34%2025.536%2" +
    "0262.979%2031.8459%20264.349%2039.6257L273.96%2094.1559H273.95C276.75%20110.076%20266.079%20125.386%20250.189%" +
    "20128.286L247.08%20128.866C247.69%20130.536%20248.2%20132.276%20248.51%20134.076L257.929%20187.566C260.769%202" +
    "03.576%20250.029%20218.886%20234.019%20221.726L62.4099%20251.976C49.2399%20254.296%2044.0596%20247.736%2030.87" +
    "96%20235.996C19.5299%20225.836%2011.7302%20220.566%209.53002%20210.346L0.959706%20161.736C-0.840294%20151.396%" +
    "20-0.44045%20145.475%205.04955%20137.425C10.1096%20129.986%2018.4103%20126.206%2028.9001%20124.346C28.3501%201" +
    "22.766%2027.8693%20121.146%2027.5593%20119.406L20.0593%2076.8356C18.0493%2065.4559%2015.5198%2054.326%2024.059" +
    "3%2042.7663C31.4393%2032.7963%2041.0097%2030.5656%2052.9997%2028.7956L211.83%200.436221ZM94.4099%20174.725C92." +
    "2099%20176.735%2087.1797%20179.395%2081.9997%20178.175C77.4897%20177.105%2074.2192%20173.175%2073.3992%20168.5" +
    "95L68.7292%20142.095L33.2898%20148.345C27.9798%20149.295%2024.4191%20154.356%2025.3591%20159.656L33.1697%20203" +
    ".906C34.1199%20209.216%2039.1795%20212.785%2044.4792%20211.836V211.866L44.49%20211.876L206.889%20183.235C212.1" +
    "99%20182.285%20215.759%20177.226%20214.819%20171.916L207.01%20127.635C206.059%20122.325%20200.999%20118.756%20" +
    "195.689%20119.706C190.379%20120.656%20184.369%20121.715%20181.359%20122.235C176.599%20123.085%20168.549%20120." +
    "135%20167.179%20112.355L164.679%2098.1755L94.4099%20174.725ZM222.81%2033.2556C221.86%2027.9158%20216.8%2024.38" +
    "59%20211.46%2025.3259L50.6804%2053.9558C45.3704%2054.9058%2041.84%2059.9663%2042.78%2065.2663L50.7703%20110.55" +
    "6V110.596C51.7204%20115.906%2056.78%20119.476%2062.0798%20118.526L76.4802%20115.996C83.13%20114.836%2089.4402%" +
    "20119.256%2090.6003%20125.876L93.1003%20140.056L163.37%2063.5056C166.51%2060.0857%20171.33%2058.7464%20175.78%" +
    "2060.0563C180.23%2061.3664%20183.59%2065.0865%20184.38%2069.6364L189.05%2096.0759L222.93%2089.9157C228.21%2088" +
    ".9355%20231.739%2083.906%20230.8%2078.6061L222.81%2033.2556Z%22%20fill=%22black%22/%3E%0A%3C/svg%3E"

private const val GoogleIconDataUri =
    "data:image/svg+xml;utf8," +
        "%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%2024%2024'%3E" +
        "%3Cpath%20fill='%23EA4335'%20d='M12%2010.2v3.9h5.4c-.2%201.3-.9%202.4-1.9%203.2l3.1%202.4c1.8-1.7%202.9-4.1%202.9-7%200-.7-.1-1.4-.2-2H12Z'/%3E" +
        "%3Cpath%20fill='%2334A853'%20d='M12%2021.6c2.6%200%204.9-.9%206.5-2.4l-3.1-2.4c-.9.6-2%20.9-3.4.9-2.6%200-4.8-1.8-5.6-4.1l-3.2%202.5c1.6%203.2%204.9%205.5%208.8%205.5Z'/%3E" +
        "%3Cpath%20fill='%234285F4'%20d='M6.4%2013.6c-.2-.6-.3-1.1-.3-1.7s.1-1.2.3-1.7L3.2%207.7C2.4%209.2%202%2010.5%202%2012s.4%202.8%201.2%204.3l3.2-2.7Z'/%3E" +
        "%3Cpath%20fill='%23FBBC05'%20d='M12%206.2c1.4%200%202.7.5%203.7%201.4l2.8-2.8C16.8%203.2%2014.6%202.4%2012%202.4c-3.9%200-7.2%202.2-8.8%205.4l3.2%202.5C7.2%207.9%209.4%206.2%2012%206.2Z'/%3E" +
        "%3C/svg%3E"

private const val GitHubIconDataUri =
    "data:image/svg+xml;utf8," +
        "%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%2024%2024'%3E" +
        "%3Cpath%20fill='%23FFFFFF'%20d='M12%20.5C5.6.5.5%205.7.5%2012.2c0%205.2%203.4%209.7%208.1%2011.2.6.1.8-.3.8-.6v-2.2c-3.3.7-4-1.4-4-1.4-.5-1.4-1.3-1.8-1.3-1.8-1.1-.8.1-.8.1-.8%201.2.1%201.9%201.3%201.9%201.3%201.1%201.9%202.8%201.3%203.5%201%20.1-.8.4-1.3.7-1.7-2.7-.3-5.5-1.4-5.5-6%200-1.3.5-2.5%201.3-3.3-.1-.3-.6-1.6.1-3.2%200%200%201.1-.4%203.5%201.3a11.8%2011.8%200%200%201%206.4%200c2.4-1.7%203.5-1.3%203.5-1.3.7%201.6.2%202.9.1%203.2.8.9%201.3%202%201.3%203.3%200%204.7-2.8%205.7-5.6%206%20.5.4.8%201.2.8%202.3v3.4c0%20.3.2.8.8.6%204.7-1.5%208.1-5.9%208.1-11.2C23.5%205.7%2018.4.5%2012%20.5Z'/%3E" +
        "%3C/svg%3E"

@Composable
private fun AuthComponentPreview() {
    AuthScaffold {
        AuthBrandBlock()
        Text(
            text = "Sign in",
            style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthTextField(value = "", onValueChange = {}, label = "Email", required = true, forceFocused = true)
        AuthTextField(value = "not-an-email", onValueChange = {}, label = "Email", required = true, isError = true, errorText = "Enter a valid email.")
        AuthActionButton(text = "Sign in", onClick = {}, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Continue with GitHub", onClick = {}, tone = AuthButtonTone.Black, preserveDisabledVisual = true)
        AuthSuccessNotice("Reset link sent", "Check artin@slock.ai for your secure link.")
    }
}
