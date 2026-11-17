package org.victoriousframework.victorlink64.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreOptionMappingTest {
    @Test fun pspResolutionStepsMatchThePpssppSizes() {
        fun res(r: Resolution) = CoreOptionMapping.psp(GraphicsSettings(resolution = r)).vars["ppsspp_internal_resolution"]
        assertEquals("480x272", res(Resolution.ORIGINAL))
        assertEquals("960x544", res(Resolution.X2))
        assertEquals("1440x816", res(Resolution.X3))
        assertEquals("1440x816", res(Resolution.P720))
        assertEquals("1920x1088", res(Resolution.P1080))
    }

    @Test fun pspQualityOptionsAreAlwaysFullyStated() {
        val o = CoreOptionMapping.psp(GraphicsSettings(Resolution.X2, TexFilter.SMOOTH, anisotropy = 8, msaa = 4, textureEnhance = TexEnhance.X2, postFx = PostFx.FXAA))
        assertEquals("Linear", o.vars["ppsspp_texture_filtering"])
        assertEquals("8x", o.vars["ppsspp_texture_anisotropic_filtering"])
        assertEquals("x4", o.vars["ppsspp_mulitsample_level"])
        assertEquals("xbrz", o.vars["ppsspp_texture_scaling_type"])
        assertEquals("2x", o.vars["ppsspp_texture_scaling_level"])
        assertEquals("enabled", o.vars["ppsspp_texture_deposterize"])
        assertEquals(PostFx.FXAA, o.postFx)
        assertFalse(o.nearest)
    }

    @Test fun pspOffValuesResetEverythingSoStepDownsReallyLowerQuality() {
        val o = CoreOptionMapping.psp(GraphicsSettings())
        assertEquals("Auto", o.vars["ppsspp_texture_filtering"])
        assertEquals("disabled", o.vars["ppsspp_texture_anisotropic_filtering"])
        assertEquals("Disabled", o.vars["ppsspp_mulitsample_level"])
        assertEquals("disabled", o.vars["ppsspp_texture_scaling_level"])
        assertEquals("disabled", o.vars["ppsspp_texture_deposterize"])
    }

    @Test fun pspSharpFilterIsNearest() {
        assertEquals("Nearest", CoreOptionMapping.psp(GraphicsSettings(textureFilter = TexFilter.SHARP)).vars["ppsspp_texture_filtering"])
    }

    @Test fun dolphinResolutionAndFiltering() {
        fun efb(r: Resolution) = CoreOptionMapping.dolphin(GraphicsSettings(resolution = r)).vars["dolphin_efb_scale"]
        assertEquals("x1", efb(Resolution.ORIGINAL))
        assertEquals("x2", efb(Resolution.X2))
        assertEquals("x2", efb(Resolution.P720))
        assertEquals("x3", efb(Resolution.X3))
        assertEquals("x3", efb(Resolution.P1080))
        val o = CoreOptionMapping.dolphin(GraphicsSettings(anisotropy = 16, msaa = 4))
        assertEquals("x16", o.vars["dolphin_max_anisotropy"])
        assertEquals("4x MSAA", o.vars["dolphin_anti_aliasing"])
        assertEquals("None", CoreOptionMapping.dolphin(GraphicsSettings()).vars["dolphin_anti_aliasing"])
    }

    @Test fun dolphinWidescreenOnlyWhenAskedFor() {
        assertNull(CoreOptionMapping.dolphin(GraphicsSettings()).vars["dolphin_widescreen_hack"])
        assertEquals("enabled", CoreOptionMapping.dolphin(GraphicsSettings(widescreen = true)).vars["dolphin_widescreen_hack"])
    }

    @Test fun dolphinDoesNotSendUnverifiedTextureFilterValues() {
        val o = CoreOptionMapping.dolphin(GraphicsSettings(textureFilter = TexFilter.SMOOTH))
        assertFalse(o.vars.containsKey("dolphin_force_texture_filtering_mode"))
    }

    @Test fun everyVariableNameIsOneOfTheCoreOwnOptions() {
        val all = GraphicsSettings(Resolution.P1080, TexFilter.SMOOTH, 16, 8, true, TexEnhance.X4, PostFx.CRT)
        CoreOptionMapping.psp(all).vars.keys.forEach { assertTrue(it, it.startsWith("ppsspp_")) }
        CoreOptionMapping.dolphin(all).vars.keys.forEach { assertTrue(it, it.startsWith("dolphin_")) }
    }

    @Test fun snesUsesTheGlPassOnlyWhenAnEffectOrSharpScalingIsChosen() {
        assertFalse(CoreOptionMapping.snesNeedsGl(GraphicsSettings()))
        assertFalse(CoreOptionMapping.snesNeedsGl(GraphicsSettings(textureFilter = TexFilter.SMOOTH)))
        assertTrue(CoreOptionMapping.snesNeedsGl(GraphicsSettings(postFx = PostFx.SMOOTH_PIXELS)))
        assertTrue(CoreOptionMapping.snesNeedsGl(GraphicsSettings(textureFilter = TexFilter.SHARP)))
        assertTrue(CoreOptionMapping.snes(GraphicsSettings(textureFilter = TexFilter.SHARP)).nearest)
    }

    @Test fun postFxIdsMatchTheNativeEnum() {
        assertEquals(listOf(0, 1, 2, 3, 4), PostFx.entries.map { it.id })
        PostFx.entries.forEach { assertEquals(it, PostFx.fromId(it.id)) }
        assertEquals(PostFx.NONE, PostFx.fromId(99))
    }

    @Test fun n64ResolutionMapsToGlideN64Factors() {
        fun o(r: Resolution) = CoreOptionMapping.n64(GraphicsSettings(resolution = r), true, false)
        assertEquals("1", o(Resolution.ORIGINAL).profile["UseNativeResolutionFactor"])
        assertEquals("2", o(Resolution.X2).profile["UseNativeResolutionFactor"])
        assertEquals("3", o(Resolution.X3).profile["UseNativeResolutionFactor"])
        assertNull(o(Resolution.X3).renderHeight)
        assertEquals("0", o(Resolution.P720).profile["UseNativeResolutionFactor"])
        assertEquals(720, o(Resolution.P720).renderHeight)
        assertEquals(1080, o(Resolution.P1080).renderHeight)
    }

    @Test fun n64FilteringAndTextures() {
        val o = CoreOptionMapping.n64(GraphicsSettings(textureFilter = TexFilter.SMOOTH, anisotropy = 8, textureEnhance = TexEnhance.X2), true, false)
        assertEquals("True", o.profile["bilinearMode"])
        assertEquals("8", o.profile["MaxAnisotropy"])
        assertEquals("9", o.profile["txEnhancementMode"])
        assertEquals("False", CoreOptionMapping.n64(GraphicsSettings(textureFilter = TexFilter.SHARP), true, false).profile["bilinearMode"])
        assertFalse(CoreOptionMapping.n64(GraphicsSettings(), true, false).profile.containsKey("bilinearMode"))
        assertEquals("0", CoreOptionMapping.n64(GraphicsSettings(), true, false).profile["MaxAnisotropy"])
        assertEquals("11", CoreOptionMapping.n64(GraphicsSettings(textureEnhance = TexEnhance.X4), true, false).profile["txEnhancementMode"])
    }

    @Test fun n64MsaaNeedsGles31() {
        assertEquals("4", CoreOptionMapping.n64(GraphicsSettings(msaa = 4), true, false).profile[CoreOptionMapping.MSAA_KEY])
        assertEquals("0", CoreOptionMapping.n64(GraphicsSettings(msaa = 4), false, false).profile[CoreOptionMapping.MSAA_KEY])
    }

    @Test fun n64WidescreenIsOnlyForcedOffWhenTheUserChoseManually() {
        assertEquals("True", CoreOptionMapping.n64(GraphicsSettings(widescreen = true), true, false).profile["WidescreenHack"])
        assertFalse(CoreOptionMapping.n64(GraphicsSettings(), true, explicitWidescreen = false).profile.containsKey("WidescreenHack"))
        assertEquals("False", CoreOptionMapping.n64(GraphicsSettings(), true, explicitWidescreen = true).profile["WidescreenHack"])
    }

    @Test fun n64ShaderChainUsesTheExistingLoaders() {
        fun fx(p: PostFx) = CoreOptionMapping.n64(GraphicsSettings(postFx = p), true, false).shaders
        assertEquals(emptyList<String>(), fx(PostFx.NONE))
        assertEquals(listOf("FXAA"), fx(PostFx.FXAA))
        assertEquals(listOf("CTR_GEOM"), fx(PostFx.CRT))
        assertEquals(listOf("SCALEFX"), fx(PostFx.SMOOTH_PIXELS))
        assertEquals(emptyList<String>(), fx(PostFx.SHARPEN))
    }
}
