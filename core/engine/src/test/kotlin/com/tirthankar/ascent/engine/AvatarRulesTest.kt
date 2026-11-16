package com.tirthankar.ascent.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarRulesTest {

    private val avatar = TestContent.bundle.avatar

    @Test
    fun `particles grow every two levels up to sixty`() {
        assertEquals(8, AvatarRules.particles(1, avatar))
        assertEquals(13, AvatarRules.particles(10, avatar))
        assertEquals(58, AvatarRules.particles(100, avatar))
        assertEquals(60, AvatarRules.particles(140, avatar))
        assertEquals(60, AvatarRules.particles(500, avatar))
    }

    @Test
    fun `one orbit ring per ten levels, at most ten`() {
        assertEquals(0, AvatarRules.rings(9, avatar))
        assertEquals(1, AvatarRules.rings(10, avatar))
        assertEquals(7, AvatarRules.rings(75, avatar))
        assertEquals(10, AvatarRules.rings(130, avatar))
    }

    @Test
    fun `every rank has an aura and every class a layer`() {
        assertEquals(AuraToken.FAINT_GREY_BLUE, AvatarRules.aura(Rank.E, avatar))
        assertEquals(AuraToken.WHITE_BLUE_BLAZE, AvatarRules.aura(Rank.S, avatar))
        assertTrue(TestContent.bundle.classes.classes.all { it.id in avatar.classLayers })
    }

    @Test
    fun `template tags name real templates and known tags`() {
        TemplateTags.all.forEach { (key, tags) ->
            assertTrue(TemplateLibrary[key] != null, key)
            assertTrue(tags.all { it in KnownTags.ALL }, "$key $tags")
        }
        assertEquals(setOf("meditation"), TemplateTags.of("self_breathing"))
        assertTrue(TemplateTags.of(null).isEmpty())
    }
}
