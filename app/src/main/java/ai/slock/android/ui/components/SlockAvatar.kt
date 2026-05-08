package ai.slock.android.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SlockAvatar(
    avatarUrl: String?,
    avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    fontSize: TextUnit = 11.sp,
    fallbackColor: Color = SlockColor.AccentLavender
) {
    val avatarWireValue = avatar?.wireValue ?: avatarUrl
    val pixelSpec = remember(avatarWireValue) { avatarWireValue?.toSharedPixelAvatarSpec() }
    when {
        pixelSpec != null -> PixelAvatar(spec = pixelSpec, modifier = modifier, size = size)
        avatarWireValue?.isHttpAvatarUrl() == true -> UrlAvatar(
            url = avatarWireValue,
            label = label,
            modifier = modifier,
            size = size,
            fontSize = fontSize,
            fallbackColor = fallbackColor
        )
        else -> InitialsAvatar(
            label = label,
            modifier = modifier,
            size = size,
            fontSize = fontSize,
            fallbackColor = fallbackColor
        )
    }
}

@Composable
private fun UrlAvatar(
    url: String,
    label: String,
    modifier: Modifier,
    size: Dp,
    fontSize: TextUnit,
    fallbackColor: Color
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Box(
            modifier = modifier
                .size(size)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
        ) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        InitialsAvatar(
            label = label,
            modifier = modifier,
            size = size,
            fontSize = fontSize,
            fallbackColor = fallbackColor
        )
    }
}

@Composable
private fun PixelAvatar(
    spec: SharedPixelAvatarSpec,
    modifier: Modifier,
    size: Dp
) {
    Canvas(
        modifier = modifier
            .size(size)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
    ) {
        val cellSize = this.size.width / PixelAvatarGridSize
        drawRect(spec.background)
        spec.grid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                val color = PixelAvatarPalette[key] ?: Color.Transparent
                if (color != Color.Transparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(columnIndex * cellSize, rowIndex * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun InitialsAvatar(
    label: String,
    modifier: Modifier,
    size: Dp,
    fontSize: TextUnit,
    fallbackColor: Color
) {
    Box(
        modifier = modifier
            .size(size)
            .background(fallbackColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.take(2).uppercase(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, fontSize = fontSize),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

private data class SharedPixelAvatarSpec(
    val background: Color,
    val grid: List<String>
)

private const val PixelAvatarGridSize = 8f
private const val PixelAvatarProtocolPrefix = "pixel:"
private const val SharedPixelAvatarRandomPrefix = "random:"
private const val PixelAvatarDefaultKey = "robot"

private val PixelAvatarPalette = mapOf(
    '_' to Color.Transparent,
    'K' to Color(0xFF141111),
    'W' to Color(0xFFFFFFFF),
    'P' to Color(0xFFFE7DA8),
    'O' to Color(0xFFF8A16F),
    'Y' to Color(0xFFFFD440),
    'L' to Color(0xFFA9D877),
    'C' to Color(0xFF27CCF3),
    'V' to Color(0xFFBBAFE6),
    'B' to Color(0xFF576574),
    'R' to Color(0xFFF97264),
    'G' to Color(0xFF9DCAAA)
)

private val PixelAvatarFixedSpecs = mapOf(
    "robot" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('C'),
        grid = listOf(
            "_KKKKKK_",
            "KCKWWKCK",
            "KWKWWKWK",
            "KKKKKKKK",
            "_KWKKWK_",
            "_KKKKKK_",
            "_KK__KK_",
            "_KK__KK_"
        )
    ),
    "cat" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('Y'),
        grid = listOf(
            "K______K",
            "KK____KK",
            "KYKYYKYK",
            "KYYYYYYK",
            "KYKYYKYK",
            "_KYYYYK_",
            "__KYYK__",
            "___KK___"
        )
    ),
    "ghost" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('V'),
        grid = listOf(
            "__KKKK__",
            "_KWWWWK_",
            "KWKWWKWK",
            "KWWWWWWK",
            "KWWWWWWK",
            "KWWKKWWK",
            "KWKWWKWK",
            "K_K__K_K"
        )
    ),
    "skull" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('O'),
        grid = listOf(
            "_KKKKKK_",
            "KWWWWWWK",
            "KWKWWKWK",
            "KWWWWWWK",
            "_KWWWWK_",
            "_KWKWKK_",
            "__KKKK__",
            "___KK___"
        )
    ),
    "alien" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('L'),
        grid = listOf(
            "_KKKKKK_",
            "KGGGGGGK",
            "KGKKKKGK",
            "KGGGGGGK",
            "_KGGGGK_",
            "__KGGK__",
            "__KGGK__",
            "_KK__KK_"
        )
    ),
    "heart" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('P'),
        grid = listOf(
            "________",
            "_KK__KK_",
            "KRRKKRRK",
            "KRRRRRRK",
            "KRRRRRRK",
            "_KRRRRK_",
            "__KRRK__",
            "___KK___"
        )
    ),
    "star" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('Y'),
        grid = listOf(
            "___KK___",
            "___KK___",
            "KKKKKKKK",
            "_KYYYYK_",
            "__KYYK__",
            "_KYKKYK_",
            "KYK__KYK",
            "KK____KK"
        )
    ),
    "flame" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('O'),
        grid = listOf(
            "___K____",
            "__KRK___",
            "__KRKK__",
            "_KORRRK_",
            "_KOORRK_",
            "KYOOORK_",
            "KYYOOK__",
            "_KKKK___"
        )
    ),
    "diamond" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('C'),
        grid = listOf(
            "___KK___",
            "__KCCK__",
            "_KCWCCK_",
            "KCWCCCCK",
            "KCCCCCCK",
            "_KCCCCK_",
            "__KCCK__",
            "___KK___"
        )
    ),
    "mushroom" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('P'),
        grid = listOf(
            "__KKKK__",
            "_KRWWRK_",
            "KRRWWRRK",
            "KRRRRRRK",
            "_KKKKKK_",
            "__KWWK__",
            "__KWWK__",
            "_KKKKKK_"
        )
    ),
    "eye" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('V'),
        grid = listOf(
            "________",
            "__KKKK__",
            "_KWWWWK_",
            "KWWKKWWK",
            "KWWKKWWK",
            "_KWWWWK_",
            "__KKKK__",
            "________"
        )
    ),
    "crown" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('Y'),
        grid = listOf(
            "________",
            "_K_KK_K_",
            "_KKKKKK_",
            "_KYYYYK_",
            "_KYYYYK_",
            "KKKKKKKK",
            "KYYYYYYK",
            "KKKKKKKK"
        )
    ),
    "cloud" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('C'),
        grid = listOf(
            "________",
            "__KK____",
            "_KWWKK__",
            "KWWWWWK_",
            "KWWWWWWK",
            "KWWWWWWK",
            "_KKKKKK_",
            "________"
        )
    ),
    "sun" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('R'),
        grid = listOf(
            "_K_KK_K_",
            "K_KYYK_K",
            "_KYYYYK_",
            "KYYYYYYK",
            "KYYYYYYK",
            "_KYYYYK_",
            "K_KYYK_K",
            "_K_KK_K_"
        )
    ),
    "bell" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('B'),
        grid = listOf(
            "___KK___",
            "__KYYK__",
            "_KYYYYK_",
            "_KYYYYK_",
            "_KYYYYK_",
            "KYYYYYYK",
            "KKKKKKKK",
            "___KK___"
        )
    ),
    "tree" to SharedPixelAvatarSpec(
        background = PixelAvatarPalette.getValue('L'),
        grid = listOf(
            "___KK___",
            "__KGGK__",
            "_KGGGGK_",
            "KGGGGGGK",
            "KGGGGGGK",
            "_KKGGKK_",
            "__KOOK__",
            "___KK___"
        )
    )
)

private val SharedPixelAvatarRandomOptions = listOf(
    PixelAvatarPalette.getValue('C') to 'K',
    PixelAvatarPalette.getValue('Y') to 'K',
    PixelAvatarPalette.getValue('L') to 'K',
    PixelAvatarPalette.getValue('P') to 'W',
    PixelAvatarPalette.getValue('V') to 'K',
    Color(0xFF1E1E1C) to 'C',
    Color(0xFF1E1E1C) to 'G',
    Color(0xFF1E1E1C) to 'P',
    Color(0xFF1E1E1C) to 'Y',
    Color(0xFF1E1E1C) to 'V',
    PixelAvatarPalette.getValue('O') to 'K',
    PixelAvatarPalette.getValue('C') to 'W'
)

private fun String.toSharedPixelAvatarSpec(): SharedPixelAvatarSpec? {
    val pixelKey = removePrefixOrNull(PixelAvatarProtocolPrefix)
    if (pixelKey != null) {
        return PixelAvatarFixedSpecs[pixelKey] ?: PixelAvatarFixedSpecs.getValue(PixelAvatarDefaultKey)
    }
    val randomSeed = removePrefixOrNull(SharedPixelAvatarRandomPrefix)
    if (randomSeed != null) {
        return randomPixelAvatar(randomSeed)
    }
    return null
}

private fun String.isHttpAvatarUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun String.removePrefixOrNull(prefix: String): String? =
    takeIf { it.startsWith(prefix) }?.substring(prefix.length)

private fun randomPixelAvatar(seed: String): SharedPixelAvatarSpec {
    val random = SharedPixelAvatarRandom(pixelHash(seed))
    val (background, fill) = SharedPixelAvatarRandomOptions[(random.next() * SharedPixelAvatarRandomOptions.size).toInt()]
    val grid = List(8) {
        val left = List(4) { if (random.next() < 0.45) fill else '_' }
        (left + left.asReversed()).joinToString(separator = "")
    }
    return SharedPixelAvatarSpec(background = background, grid = grid)
}

private fun pixelHash(value: String): Int {
    var hash = 0
    value.forEach { char ->
        hash = (hash shl 5) - hash + char.code
    }
    return hash
}

private class SharedPixelAvatarRandom(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 1831565813
        var mixed = (value xor (value ushr 15)) * (1 or value)
        mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed
        val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL
        return unsigned.toDouble() / 4294967296.0
    }
}
