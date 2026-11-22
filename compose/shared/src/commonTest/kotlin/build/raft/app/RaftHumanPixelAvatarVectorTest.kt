package build.raft.app

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
}
