// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine

import io.advancex.engine.replacements.ArgbImage
import io.advancex.engine.replacements.AssetCache
import io.advancex.engine.replacements.ImageCodec
import io.advancex.engine.replacements.NearestUpscaler
import io.advancex.engine.replacements.Scale2xUpscaler
import io.advancex.engine.replacements.Upscalers
import io.advancex.engine.safety.EnhancementGuard
import io.advancex.engine.studio.AssetDumpWriter
import io.advancex.engine.studio.DumpedAsset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetsAndSafetyTest {
    private val o = 0xFF000000.toInt() // opaque black
    private val w = 0xFFFFFFFF.toInt() // opaque white

    @Test
    fun scale2xSmoothsDiagonalsWithoutNewColours() {
        // A diagonal line: Scale2x fills the staircase corners.
        val src = ArgbImage(3, 3, intArrayOf(w, o, o, o, w, o, o, o, w))
        val out = Scale2xUpscaler().upscale(src)
        assertEquals(6, out.width)
        assertEquals(6, out.height)
        assertTrue(out.pixels.all { it == o || it == w })
        // The centre pixel's top-right sub-pixel stays white (part of the diagonal).
        assertEquals(w, out[2, 2])
        // Flat images are unchanged apart from size.
        val flat = Scale2xUpscaler().upscale(ArgbImage(2, 2, IntArray(4) { w }))
        assertTrue(flat.pixels.all { it == w })
    }

    @Test
    fun nearestUpscalerAndRegistry() {
        val out = NearestUpscaler(4).upscale(ArgbImage(1, 2, intArrayOf(w, o)))
        assertEquals(4, out.width)
        assertEquals(8, out.height)
        assertEquals(w, out[3, 3])
        assertEquals(o, out[0, 4])
        assertNull(Upscalers.byId("ai")!!.upscale(out)) // AI slot: coming later, never required
    }

    @Test
    fun assetCacheRoundTripsAndEvicts() {
        val dir = TestFixtures.tempDir("cache")
        val cache = AssetCache(dir, maxBytes = 4096)
        val img = ArgbImage(16, 16, IntArray(256) { it * 31 })
        var computed = 0
        val a = cache.getOrPut(byteArrayOf(1, 2, 3), "scale2x") { computed++; img }
        val b = cache.getOrPut(byteArrayOf(1, 2, 3), "scale2x") { computed++; img }
        assertEquals(1, computed)
        assertEquals(img, a)
        assertEquals(img, b)
        // Different process -> different key.
        assertTrue(cache.keyFor(byteArrayOf(1), "a") != cache.keyFor(byteArrayOf(1), "b"))
        // Fill beyond the budget: old entries are evicted.
        repeat(20) { i -> cache.put(cache.keyFor(byteArrayOf(i.toByte()), "x"), ArgbImage(32, 32, IntArray(1024) { it * i })) }
        assertTrue(cache.sizeBytes() <= 4096)
    }

    @Test
    fun enhancementGuardDetectsUncleanExit() {
        val guard = EnhancementGuard(File(TestFixtures.tempDir("guard"), "session.json"))
        assertNull(guard.consumeUncleanExit())
        guard.sessionStarted(EnhancementGuard.Marker("ZAXT_4edb7d35", true, listOf("patches"), 1L))
        guard.sessionEnded()
        assertNull(guard.consumeUncleanExit())

        guard.sessionStarted(EnhancementGuard.Marker("ZAXT_4edb7d35", true, listOf("patches"), 2L))
        // ...process dies here...
        val marker = assertNotNull(guard.consumeUncleanExit())
        assertTrue(EnhancementGuard.shouldEnterSafeMode(marker))
        assertNull(guard.consumeUncleanExit()) // consumed
        assertTrue(!EnhancementGuard.shouldEnterSafeMode(marker.copy(advanceMode = false)))
    }

    @Test
    fun assetDumpWriterBuildsTemplate() {
        val dir = TestFixtures.tempDir("dump")
        val codec = object : ImageCodec {
            override fun decode(bytes: ByteArray): ArgbImage? = null
            override fun encodePng(image: ArgbImage): ByteArray = byteArrayOf(1, 2, 3)
        }
        val writer = AssetDumpWriter(dir, codec)
        assertTrue(writer.write("G", DumpedAsset(0x845471e4b21baf09uL.toLong(), ArgbImage(1, 1, intArrayOf(w)))))
        assertTrue(!writer.write("G", DumpedAsset(0x845471e4b21baf09uL.toLong(), ArgbImage(1, 1, intArrayOf(w)))))
        assertEquals(1, writer.count("G"))
        assertTrue(writer.templateFile("G").readText().contains("845471e4b21baf09"))
    }
}
