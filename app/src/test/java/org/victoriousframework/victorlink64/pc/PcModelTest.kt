package org.victoriousframework.victorlink64.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcModelTest {
    @Test fun parsesPlainIp() = assertEquals("192.168.1.20" to 47989, parseHostInput(" 192.168.1.20 "))
    @Test fun parsesIpWithPort() = assertEquals("10.0.0.5" to 50000, parseHostInput("10.0.0.5:50000"))
    @Test fun parsesHostname() = assertEquals("gaming-pc.local" to 47989, parseHostInput("gaming-pc.local"))
    @Test fun parsesBracketedIpv6() = assertEquals("fe80::1" to 47989, parseHostInput("[fe80::1]"))
    @Test fun rejectsJunk() {
        assertNull(parseHostInput(""))
        assertNull(parseHostInput("my pc"))
        assertNull(parseHostInput("1.2.3.4:99999"))
        assertNull(parseHostInput("1.2.3.4:abc"))
    }

    @Test fun hostJsonRoundTrip() {
        val hs = listOf(PcHost("u1", "Desk", "192.168.1.2", serverCertB64 = "QUJD", manual = true), PcHost("u2", "Lap", "192.168.1.3"))
        val back = PcHost.listFromJson(PcHost.listToJson(hs))
        assertEquals(hs, back)
        assertTrue(back[0].paired)
        assertEquals(false, back[1].paired)
    }

    @Test fun badJsonGivesEmptyList() = assertEquals(emptyList<PcHost>(), PcHost.listFromJson("not json"))

    @Test fun upsertKeepsKnownCert() {
        val old = listOf(PcHost("u1", "Desk", "192.168.1.2", serverCertB64 = "QUJD"))
        val merged = PcHost.upsert(old, PcHost("u1", "Desk (new name)", "192.168.1.9"))
        assertEquals(1, merged.size)
        assertEquals("QUJD", merged[0].serverCertB64)
        assertEquals("192.168.1.9", merged[0].address)
    }

    @Test fun recommendedBitrates() {
        assertEquals(10_000, StreamSettings(res = StreamRes.P720, fps = 60).effectiveBitrateKbps)
        assertEquals(20_000, StreamSettings(res = StreamRes.P1080, fps = 60).effectiveBitrateKbps)
        assertEquals(15_000, StreamSettings(res = StreamRes.P1080, fps = 60, bitrateKbps = 15_000).effectiveBitrateKbps)
        assertTrue(StreamSettings(res = StreamRes.P1080, fps = 30).effectiveBitrateKbps < 20_000)
    }

    @Test fun stickAndTriggerScaling() {
        assertEquals(32767.toShort(), stickToShort(1f))
        assertEquals((-32767).toShort(), stickToShort(1f, invert = true))
        assertEquals(0.toShort(), stickToShort(0f))
        assertEquals(255.toByte(), triggerToByte(2f))
        assertEquals(0.toByte(), triggerToByte(-1f))
    }

    @Test fun deadZoneCentresSmallInput() {
        assertEquals(0f to 0f, deadZone(0.05f, 0.05f))
        val (x, _) = deadZone(1f, 0f); assertEquals(1f, x, 1e-4f)
    }

    @Test fun padFlagsMatchMoonlight() {
        // Values must equal com.limelight.nvstream.input.ControllerPacket.
        assertEquals(com.limelight.nvstream.input.ControllerPacket.A_FLAG, PadFlags.A)
        assertEquals(com.limelight.nvstream.input.ControllerPacket.Y_FLAG, PadFlags.Y)
        assertEquals(com.limelight.nvstream.input.ControllerPacket.LB_FLAG, PadFlags.LB)
        assertEquals(com.limelight.nvstream.input.ControllerPacket.PLAY_FLAG, PadFlags.START)
        assertEquals(com.limelight.nvstream.input.ControllerPacket.BACK_FLAG, PadFlags.BACK)
        assertEquals(com.limelight.nvstream.input.ControllerPacket.SPECIAL_BUTTON_FLAG, PadFlags.GUIDE)
        assertEquals(com.limelight.nvstream.input.ControllerPacket.RS_CLK_FLAG, PadFlags.RS_CLICK)
    }

    @Test fun hatToDpad() {
        assertEquals(PadFlags.UP or PadFlags.LEFT, hatFlags(-1f, -1f))
        assertEquals(0, hatFlags(0f, 0.2f))
    }

    @Test fun nintendoLayoutSwapsFaceButtonsOnly() {
        assertEquals(PadFlags.B, remapFace(PadFlags.A, true))
        assertEquals(PadFlags.A, remapFace(PadFlags.B, true))
        assertEquals(PadFlags.Y, remapFace(PadFlags.X, true))
        assertEquals(PadFlags.X, remapFace(PadFlags.Y, true))
        assertEquals(PadFlags.START, remapFace(PadFlags.START, true))
        assertEquals(PadFlags.LB, remapFace(PadFlags.LB, true))
        assertEquals(PadFlags.A, remapFace(PadFlags.A, false))
    }

    @Test fun streamDefaultsAreSafe() {
        val s = StreamSettings()
        assertEquals(StreamRes.P720, s.res)
        assertEquals(60, s.fps)
        assertEquals(0, s.bitrateKbps)
        assertEquals(false, s.swapAbxy)
    }
}
