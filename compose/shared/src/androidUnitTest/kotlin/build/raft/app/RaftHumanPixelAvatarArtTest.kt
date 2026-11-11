package build.raft.app

import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generated art ([RaftHumanPixelAvatarArtData]) is the vendored slock
 * `humanAvatarLayers.json` (resources/humanavatar, digest pinned in
 * scripts/check_human_pixel_avatar_vector.sh), and the default recipe draws Oscar's
 * `humanAvatarDefault.png` (slock packages/shared/src/fixtures) pixel for pixel, as the web's
 * own test asserts.
 */
class RaftHumanPixelAvatarArtTest {
    private val art = RaftHumanPixelAvatarArtData

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theGeneratedArtIsTheVendoredJson() {
        val bytes = resource("/humanavatar/humanAvatarLayers.json")
        assertEquals(RAFT_HUMAN_PIXEL_AVATAR_ART_SHA256, sha256(bytes), "regenerate: node scripts/generate_human_pixel_avatars.mjs")
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(json.getValue("artVersion").jsonPrimitive.content, art.artVersion)
        assertEquals(json.getValue("size").jsonPrimitive.int, art.size)
        fun strings(element: kotlinx.serialization.json.JsonElement) =
            element.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(strings(json.getValue("roleColors")).mapKeys { it.key.single() }, art.roleColors)
        assertEquals(strings(json.getValue("fixed")), art.fixed)
        val palettes = json.getValue("palettes").jsonObject
        assertEquals(strings(palettes.getValue("background")), art.backgroundPalette)
        assertEquals(strings(palettes.getValue("skin")), art.skinPalette)
        assertEquals(strings(palettes.getValue("hair")), art.hairPalette)
        val layers = json.getValue("layers").jsonArray
        assertEquals(layers.size, art.layers.size)
        layers.zip(art.layers).forEach { (element, layer) ->
            val wire = element.jsonObject
            assertEquals(wire.getValue("layer").jsonPrimitive.content, layer.layer)
            assertEquals(wire.getValue("option").jsonPrimitive.content, layer.option)
            assertEquals(wire["group"]?.jsonPrimitive?.content, layer.group)
            assertEquals(wire["z"]?.jsonPrimitive?.int, layer.z)
            assertEquals(wire.getValue("rows").jsonArray.map { it.jsonPrimitive.content }, layer.rows, "${layer.layer}:${layer.option}")
        }
        // The default recipe is the art's own default.
        val artDefault = json.getValue("default").jsonObject
        val options = RaftHumanPixelAvatarOptions
        val default = DefaultRaftHumanPixelAvatarRecipe
        assertEquals(artDefault.getValue("background").jsonPrimitive.content, options.getValue(RaftHumanPixelAvatarField.Background)[default.background])
        assertEquals(artDefault.getValue("skin").jsonPrimitive.content, options.getValue(RaftHumanPixelAvatarField.Skin)[default.skin])
        assertEquals(artDefault.getValue("hair").jsonPrimitive.content, options.getValue(RaftHumanPixelAvatarField.Hair)[default.hair])
        assertEquals(artDefault.getValue("hair_color").jsonPrimitive.content, options.getValue(RaftHumanPixelAvatarField.HairColor)[default.hairColor])
        assertEquals(artDefault.getValue("expression").jsonPrimitive.content, options.getValue(RaftHumanPixelAvatarField.Expression)[default.expression])
        assertEquals(
            artDefault.getValue("accessories").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            setOfNotNull(options.getValue(RaftHumanPixelAvatarField.Head)[default.head], options.getValue(RaftHumanPixelAvatarField.Face)[default.face]),
        )
    }

    @Test
    fun theDefaultRecipeDrawsOscarsDefaultPngPixelForPixel() {
        val png = ImageIO.read(resource("/humanavatar/humanAvatarDefault.png").inputStream())
        val drawn = composeRaftHumanPixelAvatar(DefaultRaftHumanPixelAvatarRecipe)
        assertEquals(drawn.size, png.width)
        assertEquals(drawn.size, png.height)
        val mismatches = mutableListOf<String>()
        for (i in 0 until drawn.size * drawn.size) {
            val x = i % drawn.size
            val y = i / drawn.size
            val want = "#%06x".format(png.getRGB(x, y) and 0xFFFFFF)
            val got = (drawn.pixels[i] ?: drawn.background).lowercase()
            if (got != want) mismatches += "($x,$y) $got != $want"
        }
        assertEquals(emptyList(), mismatches.take(5))
    }
}
