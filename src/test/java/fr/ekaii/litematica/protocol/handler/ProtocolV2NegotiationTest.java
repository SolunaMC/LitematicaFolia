package fr.ekaii.litematica.protocol.handler;

import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.protocol.ProtocolSessions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-version negotiation facts (see PROTOCOL-DELTA and
 * SERVUX_WIRE_FORMAT.md): Litematica 26.2-0.28.5+ declares
 * {@code {version: Int 2}} in its metadata request; 0.28.0-0.28.4
 * declared {@code {version: String MOD_STRING}}.
 */
final class ProtocolV2NegotiationTest {

    @Test
    void int_version_2_is_v2() {
        LitematicNbt.NbtCompound req = new LitematicNbt.NbtCompound();
        req.putInt("version", 2);
        assertEquals(ProtocolSessions.V2, MetadataHandler.detectWireVersion(req));
    }

    @Test
    void int_version_above_2_is_v2() {
        LitematicNbt.NbtCompound req = new LitematicNbt.NbtCompound();
        req.putInt("version", 3);
        assertEquals(ProtocolSessions.V2, MetadataHandler.detectWireVersion(req));
    }

    @Test
    void string_version_is_v1() {
        // 0.28.3-style request: version carries the client MOD_STRING.
        LitematicNbt.NbtCompound req = new LitematicNbt.NbtCompound();
        req.putString("version", "litematica-fabric-26.2-0.28.3");
        assertEquals(ProtocolSessions.V1, MetadataHandler.detectWireVersion(req));
    }

    @Test
    void missing_or_null_version_is_v1() {
        assertEquals(ProtocolSessions.V1, MetadataHandler.detectWireVersion(null));
        assertEquals(ProtocolSessions.V1,
                MetadataHandler.detectWireVersion(new LitematicNbt.NbtCompound()));
        LitematicNbt.NbtCompound one = new LitematicNbt.NbtCompound();
        one.putInt("version", 1);
        assertEquals(ProtocolSessions.V1, MetadataHandler.detectWireVersion(one));
    }

    @Test
    void sessions_registry_tracks_and_forgets() {
        ProtocolSessions s = new ProtocolSessions();
        UUID u = UUID.randomUUID();
        assertFalse(s.isKnown(u));
        assertFalse(s.isV2(u));
        assertEquals(7, s.versionOr(u, 7));

        s.setVersion(u, 2);
        assertTrue(s.isKnown(u));
        assertTrue(s.isV2(u));

        s.setVersion(u, 1);
        assertTrue(s.isKnown(u));
        assertFalse(s.isV2(u));

        s.forget(u);
        assertFalse(s.isKnown(u));
    }

    @Test
    void rotation_yaw_accepts_v1_strings_and_v2_ordinals() {
        // v1: enum NAME as String.
        assertEquals(0,   yawOf(str("NONE")));
        assertEquals(90,  yawOf(str("CLOCKWISE_90")));
        assertEquals(180, yawOf(str("CLOCKWISE_180")));
        assertEquals(270, yawOf(str("COUNTERCLOCKWISE_90")));
        // v2: enum ORDINAL as Int (Rotation: NONE, CW_90, CW_180, CCW_90).
        assertEquals(0,   yawOf(ordinal(0)));
        assertEquals(90,  yawOf(ordinal(1)));
        assertEquals(180, yawOf(ordinal(2)));
        assertEquals(270, yawOf(ordinal(3)));
        // Absent -> 0.
        assertEquals(0, DirectPasteHandler.readRotationYaw(new LitematicNbt.NbtCompound()));
    }

    private static LitematicNbt.NbtCompound str(String name) {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putString("Rotation", name);
        return c;
    }

    private static LitematicNbt.NbtCompound ordinal(int ord) {
        LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
        c.putInt("Rotation", ord);
        return c;
    }

    private static int yawOf(LitematicNbt.NbtCompound c) {
        return DirectPasteHandler.readRotationYaw(c);
    }
}
