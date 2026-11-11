package build.raft.app

import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberKind
import build.raft.app.model.avatarWireValueForAvatarSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Vectors copied from packages/shared/src/humanAvatar.test.ts at
//   github.com/botiverse/slock staging @ 853c6998bcef45df5c25e63b1035e2388608721d
// (feature slock #14303, art v3.2 slock #14326). Pinned with the port and
// scripts/check_human_pixel_avatar_vector.sh; bump all three together.
class RaftHumanPixelAvatarVectorTest {
    private val default = DefaultRaftHumanPixelAvatarRecipe
    private val art = RaftHumanPixelAvatarArtData

    @Test
    fun portIsPinnedToTheVectorsCommit() {
        assertEquals("853c6998bcef45df5c25e63b1035e2388608721d", RAFT_HUMAN_PIXEL_AVATAR_SLOCK_SHA)
    }

    @Test
    fun theDefaultRecipeSerializesAsTheWebDoes() {
        assertEquals("pixel-human:v1:yellow.2.short.ink.wink.cap.none", serializeRaftHumanPixelAvatarRecipe(default))
    }

    @Test
    fun everyOptionIdDrawsArtThatExists() {
        val layers = art.layers.map { "${it.layer}:${it.option}" }.toSet()
        val options = RaftHumanPixelAvatarOptions
        for (id in RaftHumanPixelAvatarField.Background.optionIds) {
            assertTrue(art.backgroundPalette.containsKey(options.getValue(RaftHumanPixelAvatarField.Background)[id]), id)
        }
        for (id in RaftHumanPixelAvatarField.Skin.optionIds) {
            assertTrue(art.skinPalette.containsKey(options.getValue(RaftHumanPixelAvatarField.Skin)[id]), id)
        }
        for (id in RaftHumanPixelAvatarField.HairColor.optionIds) {
            assertTrue(art.hairPalette.containsKey(options.getValue(RaftHumanPixelAvatarField.HairColor)[id]), id)
        }
        for (id in RaftHumanPixelAvatarField.Hair.optionIds) {
            assertTrue("hair:${options.getValue(RaftHumanPixelAvatarField.Hair)[id]}" in layers, id)
        }
        for (id in RaftHumanPixelAvatarField.Expression.optionIds) {
            assertTrue("expression:${options.getValue(RaftHumanPixelAvatarField.Expression)[id]}" in layers, id)
        }
        for (field in listOf(RaftHumanPixelAvatarField.Head, RaftHumanPixelAvatarField.Face)) {
            for (id in field.optionIds) {
                val option = options.getValue(field)[id] ?: continue
                assertTrue("accessory:$option" in layers, id)
            }
        }
        // Every accessory layer's group matches the recipe field it is offered in.
        for (layer in art.layers.filter { it.layer == "accessory" }) {
            val field = if (layer.group == "head") RaftHumanPixelAvatarField.Head else RaftHumanPixelAvatarField.Face
            assertTrue(layer.option in options.getValue(field).values, layer.option)
        }
    }

    @Test
    fun everyRecipeRoundTripsThroughItsStoredValue() {
        for (background in RaftHumanPixelAvatarField.Background.optionIds)
            for (hair in RaftHumanPixelAvatarField.Hair.optionIds)
                for (head in RaftHumanPixelAvatarField.Head.optionIds)
                    for (face in RaftHumanPixelAvatarField.Face.optionIds)
                        for (expression in RaftHumanPixelAvatarField.Expression.optionIds) {
                            val recipe =
                                default.copy(
                                    background = background, hair = hair, head = head, face = face,
                                    expression = expression, skin = "4", hairColor = "ginger",
                                )
                            val value = serializeRaftHumanPixelAvatarRecipe(recipe)
                            assertTrue(isRaftHumanPixelAvatarUrl(value), value)
                            assertEquals(recipe, parseRaftHumanPixelAvatarRecipe(value), value)
                            assertEquals(recipe, readRaftHumanPixelAvatarRecipe(value), value)
                        }
    }

    @Test
    fun parseIsStrict() {
        for (value in listOf(
            "pixel-human:v1:yellow.2.short.ink.wink.cap",
            "pixel-human:v1:yellow.2.short.ink.wink.cap.none.later",
            "pixel-human:v1:purple.2.short.ink.wink.cap.none",
            "pixel-human:v1:yellow.9.short.ink.wink.cap.none",
            "pixel-human:v1:yellow.2.short.ink.frown.cap.none",
            "pixel-human:v1:yellow.2.short.ink.wink.crown.none",
            "pixel-human:v2:yellow.2.short.ink.wink.cap.none",
            "pixel-human:v1:Yellow.2.short.ink.wink.cap.none",
            "pixel-human:v1:yellow.2.short.ink.wink.cap.none ",
            "pixel:human:v1:yellow.2.short.ink.wink.cap.none",
            "pixel:robot",
            "/api/avatars/users/0123456789abcdef0123456789abcdef.webp",
            "",
        )) {
            assertNull(parseRaftHumanPixelAvatarRecipe(value), value)
        }
        assertNull(parseRaftHumanPixelAvatarRecipe("pixel-human:v1:constructor.2.short.ink.wink.cap.none"), "prototype keys are not ids")
    }

    @Test
    fun aServerPickedAvatarCarriesTheAutoMarkerAndDrawsLikeABuiltOne() {
        val recipe = default.copy(background = "pink")
        val auto = serializeRaftHumanPixelAvatarRecipe(recipe, auto = true)
        assertEquals("pixel-human:v1:pink.2.short.ink.wink.cap.none/auto", auto)
        assertTrue(isAutoRaftHumanPixelAvatarUrl(auto))
        assertFalse(isAutoRaftHumanPixelAvatarUrl(serializeRaftHumanPixelAvatarRecipe(recipe)), "a built one has no marker")
        assertFalse(isAutoRaftHumanPixelAvatarUrl(null))
        assertEquals(recipe, parseRaftHumanPixelAvatarRecipe(auto))
        assertEquals(recipe, readRaftHumanPixelAvatarRecipe(auto))
        assertNull(parseRaftHumanPixelAvatarRecipe("pixel-human:v1:pink.2.short.ink.wink.cap.none/other"))
    }

    @Test
    fun readingForDrawingIsLenient() {
        assertEquals(
            default.copy(background = "pink", skin = "4", hair = "curly", face = "glasses"),
            readRaftHumanPixelAvatarRecipe("pixel-human:v1:pink.4.curly.ink.sneeze.crown.glasses"),
        )
        // A later build appends a field: this one draws the rest as stored.
        assertEquals(
            RaftHumanPixelAvatarRecipe("mint", "1", "side", "ginger", "grin", "headphones", "none"),
            readRaftHumanPixelAvatarRecipe("pixel-human:v1:mint.1.side.ginger.grin.headphones.none.scarf"),
        )
        // A value missing trailing fields reads each as its default.
        assertEquals(
            default.copy(background = "sky", skin = "3", hair = "bun", hairColor = "brown"),
            readRaftHumanPixelAvatarRecipe("pixel-human:v1:sky.3.bun.brown"),
        )
        // Another version draws the default avatar (the contract: "another version draws the default avatar").
        assertEquals(default, readRaftHumanPixelAvatarRecipe("pixel-human:v9:anything"))
        assertNull(readRaftHumanPixelAvatarRecipe(null))
        assertNull(readRaftHumanPixelAvatarRecipe("pixel:robot"))
        assertNull(readRaftHumanPixelAvatarRecipe("https://example.com/a.png"))
        assertNull(readRaftHumanPixelAvatarRecipe("pixel-human:v1:${"a".repeat(200)}"), "too long to be one")
    }

    @Test
    fun valuesThatAreNotOfTheSchemeDrawNothing() {
        for (value in listOf(
            "pixel-human:v1:yellow.2.short.ink.wink.cap.none ",
            "pixel-human:v1:Yellow.2.short.ink.wink.cap.none",
            "pixel-human:v1:yellow.2.short.ink.wink.cap.none/other",
            "pixel-human:vx:yellow",
            "pixel-human:v1234:yellow",
            "pixel-human:",
            "PIXEL-HUMAN:v1:yellow.2.short.ink.wink.cap.none",
        )) {
            assertNull(readRaftHumanPixelAvatarRecipe(value), value)
            assertNull(raftHumanPixelAvatarRaster(value), value)
        }
    }

    @Test
    fun anExpressionDrawsOverTheBaseAndUnderTheHair() {
        val longHair = default.copy(hair = "long")
        val plain = composeRaftHumanPixelAvatar(longHair)
        val grin = composeRaftHumanPixelAvatar(longHair.copy(expression = "grin"))
        val hair = art.layers.first { it.layer == "hair" && it.option == "long" }
        val grinLayer = art.layers.first { it.layer == "expression" && it.option == "grin" }
        var overlapping = 0
        for (y in 0 until 112) for (x in 0 until 112) if (hair.rows[y][x] != '0' && grinLayer.rows[y][x] != '0') overlapping++
        assertTrue(overlapping > 0, "the case must overlap to test the order")
        val cap = art.layers.first { it.option == "cap" }
        var changed = 0
        for (i in plain.pixels.indices) {
            val x = i % plain.size
            val y = i / plain.size
            val covered = hair.rows[y][x] != '0' || cap.rows[y][x] != '0'
            if (covered) assertEquals(plain.pixels[i], grin.pixels[i], "hair/cap cell ($x,$y) stays on top")
            else if (grin.pixels[i] != plain.pixels[i]) changed++
        }
        assertTrue(changed > 0, "the grin changes the face")
        // The blush uses role 8, the pink only the expression layers have.
        val blush = composeRaftHumanPixelAvatar(default.copy(expression = "blush"))
        assertTrue(art.fixed.getValue("pink") in blush.pixels, "role 8 draws the art's pink")
    }

    @Test
    fun accessoriesDrawByAscendingZ() {
        // Glasses (z 0) draw first, headphones (z 1) over them.
        val both = composeRaftHumanPixelAvatar(default.copy(head = "headphones", face = "glasses"))
        val headphonesOnly = composeRaftHumanPixelAvatar(default.copy(head = "headphones"))
        val headphones = art.layers.first { it.option == "headphones" }
        for (y in 0 until both.size) {
            for (x in 0 until both.size) {
                if (headphones.rows[y][x] != '0') assertEquals(headphonesOnly.pixels[y * both.size + x], both.pixels[y * both.size + x])
            }
        }
        assertEquals(112, both.size)
        assertEquals("#ffd440", composeRaftHumanPixelAvatar(default).background)
    }

    @Test
    fun everyRoleALayerUsesHasAColourAndTheHeadphonesDrawInTheirOwnReds() {
        val used = art.layers.flatMap { layer -> layer.rows.flatMap { it.toList() } }.toMutableSet()
        used.remove('0')
        for (role in used) assertTrue(art.roleColors.containsKey(role), "role $role has a colour")
        val phones = composeRaftHumanPixelAvatar(default.copy(head = "headphones"))
        for (key in listOf("phone_red", "phone_shade", "phone_band", "phone_hi")) {
            assertTrue(art.fixed.getValue(key) in phones.pixels, "$key drawn")
        }
    }

    @Test
    fun theRasterFillsTheBackgroundUnderTransparentCells() {
        val composed = composeRaftHumanPixelAvatar(default.copy(background = "lilac"))
        val raster = requireNotNull(raftHumanPixelAvatarRaster("pixel-human:v1:lilac.2.short.ink.wink.cap.none/auto"))
        assertEquals(composed.size, raster.size)
        assertEquals(raftHumanPixelAvatarArgb("#bea0f5"), raster.backgroundArgb)
        for (i in composed.pixels.indices) {
            assertEquals(raftHumanPixelAvatarArgb(composed.pixels[i] ?: composed.background), raster.argb[i], "pixel $i")
        }
        // The shared raster the home-screen widgets draw from is the same picture.
        val widgetRaster = requireNotNull(raftPixelAvatarRaster("pixel-human:v1:lilac.2.short.ink.wink.cap.none"))
        assertEquals(112, widgetRaster.cellArgb.size)
        assertEquals(raster.argb.toList(), widgetRaster.cellArgb.flatMap { it.toList() })
    }

    @Test
    fun aPersonShowsTheirBuiltAvatarAndNeverAnInvalidOne() {
        val built = "pixel-human:v1:mint.1.side.ginger.grin.headphones.none"
        assertEquals(built, avatarWireValueForAvatarSlot(MemberKind.Human, "u1", built))
        assertEquals("$built/auto", avatarWireValueForAvatarSlot(MemberKind.Human, "u1", "$built/auto"))
        assertEquals(built, avatarWireValueForAvatarSlot(MemberKind.Human, "u1", "  $built "))
        // An unknown version is still the scheme: it draws (the default look), as on the web.
        assertEquals("pixel-human:v2:anything", avatarWireValueForAvatarSlot(MemberKind.Human, "u1", "pixel-human:v2:anything"))
        for (invalid in listOf("pixel-human:v1:Yellow", "pixel-human:v1:${"a".repeat(200)}", "pixel-human:x", "https://example.com/a.png")) {
            assertNull(avatarWireValueForAvatarSlot(MemberKind.Human, "u1", invalid), invalid)
        }
        // A person's uploaded photo still wins the slot.
        val photo = "https://cdn.example/avatars/users/0123456789abcdef.webp"
        assertEquals(photo, avatarWireValueForAvatarSlot(MemberKind.Human, "u1", photo))
    }

    @Test
    fun aBuiltAvatarIsAConcreteAvatarReference() {
        val built = "pixel-human:v1:yellow.2.short.ink.wink.cap.none/auto"
        val reference = AvatarReference.fromWireValue(built)
        assertEquals(AvatarReference.HumanPixel(built), reference)
        assertEquals(built, reference?.wireValue)
        assertTrue(reference!!.isConcrete)
        assertFalse(AvatarReference.Pixel("robot").isConcrete)
        assertNull(AvatarReference.fromWireValue("pixel-human:v1:Yellow"))
    }
}
