/*
 * ProtocolBot — minimal headless Minecraft 26.1.2 client used to drive the
 * LitematicaFolia Servux bridge end-to-end (handshake → login → configuration
 * → play → C2S_METADATA_REQUEST → S2C_METADATA → C2S_NBT_STREAM_START /
 * C2S_NBT_STREAM_DATA → server-side paste).
 *
 * Reverse-engineered packet IDs (from javap against Luminol 26.1.2):
 *
 *   Login (Server→Client):  0=Disconnect 1=EncryptionRequest 2=LoginSuccess
 *                           3=SetCompression 4=PluginRequest 5=CookieRequest
 *   Login (Client→Server):  0=Hello 1=Key 2=PluginAnswer 3=LoginAck 4=CookieResp
 *
 *   Configuration (S→C):    0=CookieReq 1=CustomPayload 2=Disconnect
 *                           3=FinishConfig 4=KeepAlive 5=Ping 6=ResetChat
 *                           7=RegistryData 8=ResPackPop 9=ResPackPush 10=StoreCookie
 *                           11=Transfer 12=UpdateEnabledFeatures 13=UpdateTags
 *                           14=SelectKnownPacks ...
 *   Configuration (C→S):    0=ClientInformation 1=CookieResp 2=CustomPayload
 *                           3=FinishConfig 4=KeepAlive 5=Pong 6=ResourcePack
 *                           7=SelectKnownPacks ...
 *
 *   Play  (S→C):  0=Bundle 24=CustomPayload 32=Disconnect 44=KeepAlive
 *                 45=LevelChunkWithLight 49=Login(Play) 61=Ping
 *                 72=PlayerPosition 82=Respawn 118=StartConfiguration
 *   Play  (C→S):  22=CustomPayload 28=KeepAlive 17=ConfigurationAcknowledged
 *
 * No external libs — just java.net.Socket + DataInput/Output streams + LitematicNbt
 * (compiled into build/classes/java/main).
 */

import fr.ekaii.litematica.core.LitematicNbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class ProtocolBot {

    // ----- MC protocol version for 26.1.2 (extracted from version.json) -----
    private static final int PROTOCOL_VERSION = 776; // MC 26.2 (was 775 = 26.1.2)

    // ----- Login Server→Client -----
    private static final int LOGIN_S2C_DISCONNECT = 0;
    private static final int LOGIN_S2C_ENCRYPTION_REQUEST = 1;
    private static final int LOGIN_S2C_LOGIN_SUCCESS = 2;
    private static final int LOGIN_S2C_SET_COMPRESSION = 3;
    private static final int LOGIN_S2C_PLUGIN_REQUEST = 4;
    private static final int LOGIN_S2C_COOKIE_REQUEST = 5;

    // ----- Login Client→Server -----
    private static final int LOGIN_C2S_HELLO = 0;
    private static final int LOGIN_C2S_LOGIN_ACK = 3;
    private static final int LOGIN_C2S_COOKIE_RESPONSE = 4;
    private static final int LOGIN_C2S_PLUGIN_ANSWER = 2;

    // ----- Configuration Server→Client -----
    private static final int CONFIG_S2C_COOKIE_REQUEST = 0;
    private static final int CONFIG_S2C_CUSTOM_PAYLOAD = 1;
    private static final int CONFIG_S2C_DISCONNECT = 2;
    private static final int CONFIG_S2C_FINISH = 3;
    private static final int CONFIG_S2C_KEEP_ALIVE = 4;
    private static final int CONFIG_S2C_PING = 5;
    private static final int CONFIG_S2C_REGISTRY_DATA = 7;
    private static final int CONFIG_S2C_STORE_COOKIE = 10;
    private static final int CONFIG_S2C_TRANSFER = 11;
    private static final int CONFIG_S2C_UPDATE_ENABLED_FEATURES = 12;
    private static final int CONFIG_S2C_UPDATE_TAGS = 13;
    private static final int CONFIG_S2C_SELECT_KNOWN_PACKS = 14;

    // ----- Configuration Client→Server -----
    private static final int CONFIG_C2S_CLIENT_INFORMATION = 0;
    private static final int CONFIG_C2S_COOKIE_RESPONSE = 1;
    private static final int CONFIG_C2S_CUSTOM_PAYLOAD = 2;
    private static final int CONFIG_C2S_FINISH = 3;
    private static final int CONFIG_C2S_KEEP_ALIVE = 4;
    private static final int CONFIG_C2S_PONG = 5;
    private static final int CONFIG_C2S_RESOURCE_PACK = 6;
    private static final int CONFIG_C2S_SELECT_KNOWN_PACKS = 7;

    // ----- Play Server→Client -----
    private static final int PLAY_S2C_CUSTOM_PAYLOAD = 24;
    private static final int PLAY_S2C_DISCONNECT = 32;
    private static final int PLAY_S2C_KEEP_ALIVE = 44;
    private static final int PLAY_S2C_LOGIN = 49;
    private static final int PLAY_S2C_PING = 61;
    private static final int PLAY_S2C_PLAYER_POSITION = 72;
    private static final int PLAY_S2C_RESPAWN = 82;
    private static final int PLAY_S2C_START_CONFIGURATION = 118;

    // ----- Play Client→Server -----
    private static final int PLAY_C2S_CUSTOM_PAYLOAD = 22;
    private static final int PLAY_C2S_KEEP_ALIVE = 28;
    private static final int PLAY_C2S_CONFIGURATION_ACK = 17;
    private static final int PLAY_C2S_ACCEPT_TELEPORTATION = 0;

    // ----- Servux protocol IDs (from ProtocolConstants.Litematics) -----
    private static final int SERVUX_S2C_METADATA = 1;
    private static final int SERVUX_C2S_METADATA_REQUEST = 2;
    private static final int SERVUX_C2S_NBT_STREAM_START = 12;
    private static final int SERVUX_C2S_NBT_STREAM_DATA = 13;
    private static final int SERVUX_S2C_TASK_STATUS_SYNC = 16;

    private static final String CHANNEL_LITEMATICS = "servux:litematics";

    // ============================================================ args
    private static String username = "ProtoBot";
    private static String host = "127.0.0.1";
    private static int port = 25699;
    private static Path litematicPath;
    private static int originX = 100, originY = 64, originZ = 100;
    private static int holdSeconds = 10;
    private static boolean verbose = true;
    /**
     * Servux wire era to emulate: 1 = pre-0.28.5 (VarInt txId + vanilla
     * NBT, Litematic-Transmit sub-protocol), 2 = 26.2-0.28.5+ (metadata
     * {version: Int 2}, single Data Tag blob with Task=LitematicaPaste).
     */
    private static int wire = 1;

    public static void main(String[] args) throws Exception {
        parseArgs(args);

        log("ProtocolBot starting → " + host + ":" + port);
        log("username = " + username);
        log("litematic = " + litematicPath);
        log("origin = " + originX + "," + originY + "," + originZ);

        try (Socket sock = new Socket(host, port)) {
            sock.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());

            // ---- Handshake ----
            sendHandshake(out);
            log("[handshake] sent intent=2 (login) protocol=" + PROTOCOL_VERSION);

            // ---- Login ----
            sendLoginHello(out, username);
            log("[login] hello sent (username=" + username + ")");

            boolean loginDone = runLoginUntilSuccess(in, out);
            if (!loginDone) {
                fail("login phase failed");
            }
            log("[login] success — sending LoginAcknowledged → entering CONFIG");
            sendVarIntPacket(out, LOGIN_C2S_LOGIN_ACK, new byte[0]);

            // ---- Configuration ----
            runConfigurationUntilFinish(in, out);
            log("[config] FinishConfiguration ack sent → entering PLAY");

            // ---- Play ----
            runPlayUntilLogin(in, out);
            log("[play] received Login(Play)");

            // declare we listen on servux:litematics by sending MC's Brand custom payload
            // (optional but harmless), then request metadata.
            sendBrandCustomPayload(out);
            log("[play] sent Brand custom payload");

            // Tell Paper which channels we want to receive on (minecraft:register).
            // Paper's Messenger drops outbound payloads for unregistered channels.
            sendChannelRegister(out);
            log("[play] sent minecraft:register for servux channels");

            // Request Servux metadata
            sendServuxMetadataRequest(out);
            log("[servux] sent C2S_METADATA_REQUEST (wire v" + wire + ")");

            // Stream the litematic
            byte[] schematic = Files.readAllBytes(litematicPath);
            log("[servux] read " + schematic.length + " bytes from " + litematicPath);

            if (wire >= 2) {
                streamDirectPasteV2(out, schematic, originX, originY, originZ);
                log("[servux] streamed v2 LitematicaPaste Data Tag blob as type-13 slices");
            } else {
                streamDirectPaste(out, schematic, originX, originY, originZ);
                log("[servux] streamed C2S_NBT_STREAM_START + DATA frames");
            }

            // Pump for a while, handling keep-alives + reading metadata reply
            log("[play] pumping packets for " + holdSeconds + "s to drain replies + paste");
            long pumpDeadline = System.currentTimeMillis() + holdSeconds * 1000L;
            sock.setSoTimeout(2000);
            while (System.currentTimeMillis() < pumpDeadline) {
                try {
                    Packet p = readPacket(in);
                    handlePlayInbound(out, p);
                } catch (java.net.SocketTimeoutException timeout) {
                    // no traffic — normal
                } catch (EOFException eof) {
                    log("[play] server closed connection: " + eof.getMessage());
                    break;
                }
            }
            log("DONE");
        }
    }

    // -------------------------------------------------------------- handshake
    private static void sendHandshake(DataOutputStream out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream b = new DataOutputStream(baos);
        writeVarInt(b, PROTOCOL_VERSION);
        writeString(b, host);
        b.writeShort(port);
        writeVarInt(b, 2); // intent = login
        sendVarIntPacket(out, 0x00, baos.toByteArray());
    }

    // -------------------------------------------------------------- login
    private static void sendLoginHello(DataOutputStream out, String name) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream b = new DataOutputStream(baos);
        writeString(b, name);
        UUID uuid = offlineUUID(name);
        b.writeLong(uuid.getMostSignificantBits());
        b.writeLong(uuid.getLeastSignificantBits());
        sendVarIntPacket(out, LOGIN_C2S_HELLO, baos.toByteArray());
    }

    private static UUID offlineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean runLoginUntilSuccess(DataInputStream in, DataOutputStream out)
            throws IOException {
        while (true) {
            Packet p = readPacket(in);
            switch (p.id) {
                case LOGIN_S2C_DISCONNECT -> {
                    log("[login] disconnect: " + readString(new DataInputStream(new ByteArrayInputStream(p.data))));
                    return false;
                }
                case LOGIN_S2C_SET_COMPRESSION -> {
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    int threshold = readVarInt(d);
                    log("[login] SetCompression threshold=" + threshold + " — bot does not implement compression; relies on network-compression-threshold=-1 in server.properties");
                    // The smoke harness sets network-compression-threshold=-1
                    // (no compression). If threshold is something other than -1
                    // we cannot continue.
                    if (threshold >= 0) {
                        log("[login] FATAL: server enabled compression; bot needs compression support");
                        return false;
                    }
                }
                case LOGIN_S2C_LOGIN_SUCCESS -> {
                    // Read uuid + username (and a profile properties array) but
                    // we don't need to use them; just log.
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    long msb = d.readLong();
                    long lsb = d.readLong();
                    String n = readString(d);
                    log("[login] LoginSuccess uuid=" + new UUID(msb, lsb) + " name=" + n);
                    return true;
                }
                case LOGIN_S2C_ENCRYPTION_REQUEST -> {
                    log("[login] FATAL: server requested encryption — online-mode should be false");
                    return false;
                }
                case LOGIN_S2C_PLUGIN_REQUEST -> {
                    // Some forge-flavoured servers ask plugin queries here.
                    // Always negative-answer (id, successful=false).
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    int messageId = readVarInt(d);
                    String channel = readString(d);
                    log("[login] plugin request id=" + messageId + " channel=" + channel + " → negative answer");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream b = new DataOutputStream(baos);
                    writeVarInt(b, messageId);
                    b.writeBoolean(false);
                    sendVarIntPacket(out, LOGIN_C2S_PLUGIN_ANSWER, baos.toByteArray());
                }
                case LOGIN_S2C_COOKIE_REQUEST -> {
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    String key = readString(d);
                    log("[login] cookie request key=" + key + " → respond with empty");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream b = new DataOutputStream(baos);
                    writeString(b, key);
                    b.writeBoolean(false);
                    sendVarIntPacket(out, LOGIN_C2S_COOKIE_RESPONSE, baos.toByteArray());
                }
                default -> log("[login] unhandled packet id=" + p.id + " len=" + p.data.length);
            }
        }
    }

    // -------------------------------------------------------------- config
    private static void runConfigurationUntilFinish(DataInputStream in, DataOutputStream out)
            throws IOException {
        // Send ClientInformation up front (vanilla client does this; some
        // servers wait for it before issuing RegistryData).
        sendClientInformation(out);
        log("[config] sent ClientInformation");

        while (true) {
            Packet p = readPacket(in);
            switch (p.id) {
                case CONFIG_S2C_CUSTOM_PAYLOAD -> {
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    String chan = readString(d);
                    byte[] rest = readAll(d);
                    log("[config] custom payload channel=" + chan + " bytes=" + rest.length);
                    // Reply to Brand specifically so the server knows our brand
                    if (chan.equals("minecraft:brand")) {
                        sendCustomPayload(out, CONFIG_C2S_CUSTOM_PAYLOAD, "minecraft:brand", brandPayload());
                    }
                }
                case CONFIG_S2C_DISCONNECT -> {
                    log("[config] disconnect");
                    return;
                }
                case CONFIG_S2C_FINISH -> {
                    sendVarIntPacket(out, CONFIG_C2S_FINISH, new byte[0]);
                    return;
                }
                case CONFIG_S2C_KEEP_ALIVE -> {
                    sendVarIntPacket(out, CONFIG_C2S_KEEP_ALIVE, p.data);
                }
                case CONFIG_S2C_PING -> {
                    sendVarIntPacket(out, CONFIG_C2S_PONG, p.data);
                }
                case CONFIG_S2C_REGISTRY_DATA -> {
                    if (verbose) log("[config] RegistryData (skipped) len=" + p.data.length);
                }
                case CONFIG_S2C_UPDATE_ENABLED_FEATURES -> {
                    if (verbose) log("[config] UpdateEnabledFeatures (skipped) len=" + p.data.length);
                }
                case CONFIG_S2C_UPDATE_TAGS -> {
                    if (verbose) log("[config] UpdateTags (skipped) len=" + p.data.length);
                }
                case CONFIG_S2C_SELECT_KNOWN_PACKS -> {
                    // Vanilla client mirrors back the offered packs to opt-in
                    // (so the server doesn't have to send the embedded vanilla
                    // registry pack). We claim to know NOTHING, forcing the
                    // server to send everything (which we discard). That's the
                    // simpler path and what older mods do.
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    int n = readVarInt(d);
                    log("[config] SelectKnownPacks offer=" + n + " — replying empty (we know none)");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream b = new DataOutputStream(baos);
                    writeVarInt(b, 0);
                    sendVarIntPacket(out, CONFIG_C2S_SELECT_KNOWN_PACKS, baos.toByteArray());
                }
                case CONFIG_S2C_STORE_COOKIE -> {
                    if (verbose) log("[config] StoreCookie (ignored)");
                }
                case CONFIG_S2C_TRANSFER -> {
                    log("[config] Transfer request — bot does not follow transfers");
                    return;
                }
                case CONFIG_S2C_COOKIE_REQUEST -> {
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                    String key = readString(d);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream b = new DataOutputStream(baos);
                    writeString(b, key);
                    b.writeBoolean(false);
                    sendVarIntPacket(out, CONFIG_C2S_COOKIE_RESPONSE, baos.toByteArray());
                }
                default -> {
                    if (verbose) log("[config] unhandled packet id=" + p.id + " len=" + p.data.length);
                }
            }
        }
    }

    private static void sendClientInformation(DataOutputStream out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream b = new DataOutputStream(baos);
        // ClientInformation wire (1.21+):
        //   String locale
        //   Byte viewDistance
        //   VarInt chatMode (0=enabled)
        //   Boolean chatColors
        //   Byte displayedSkinParts (bitmask)
        //   VarInt mainHand (1=right)
        //   Boolean textFiltering
        //   Boolean allowServerListings
        //   VarInt particleStatus (0=all)
        writeString(b, "en_US");
        b.writeByte(4);                // view distance
        writeVarInt(b, 0);             // chat mode = enabled
        b.writeBoolean(true);          // chat colors
        b.writeByte(0x7F);             // displayed skin parts (all)
        writeVarInt(b, 1);             // main hand right
        b.writeBoolean(false);         // text filtering enabled
        b.writeBoolean(true);          // allow server listings
        writeVarInt(b, 0);             // particle status = all
        sendVarIntPacket(out, CONFIG_C2S_CLIENT_INFORMATION, baos.toByteArray());
    }

    // -------------------------------------------------------------- play
    private static void runPlayUntilLogin(DataInputStream in, DataOutputStream out)
            throws IOException {
        while (true) {
            Packet p = readPacket(in);
            switch (p.id) {
                case PLAY_S2C_LOGIN -> {
                    log("[play] Login(Play) packet — we are in world");
                    return;
                }
                case PLAY_S2C_KEEP_ALIVE -> {
                    sendVarIntPacket(out, PLAY_C2S_KEEP_ALIVE, p.data);
                }
                case PLAY_S2C_PING -> {
                    // Pong on play uses CommonPacketTypes — id 30 in play (we don't strictly need to)
                    log("[play] ping (pre-login)");
                }
                case PLAY_S2C_DISCONNECT -> {
                    log("[play] disconnect pre-login");
                    return;
                }
                default -> {
                    if (verbose) log("[play] pre-login packet id=" + p.id + " len=" + p.data.length);
                }
            }
        }
    }

    private static void handlePlayInbound(DataOutputStream out, Packet p) throws IOException {
        switch (p.id) {
            case PLAY_S2C_KEEP_ALIVE -> {
                if (verbose) log("[play] keepalive ping → pong");
                sendVarIntPacket(out, PLAY_C2S_KEEP_ALIVE, p.data);
            }
            case PLAY_S2C_DISCONNECT -> {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                log("[play] disconnect: " + readString(d));
            }
            case PLAY_S2C_CUSTOM_PAYLOAD -> {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                String chan = readString(d);
                byte[] rest = readAll(d);
                log("[play] CustomPayload channel=" + chan + " bytes=" + rest.length);
                if (chan.equals(CHANNEL_LITEMATICS)) {
                    decodeServuxLitematics(rest);
                }
            }
            case PLAY_S2C_PLAYER_POSITION -> {
                // Acknowledge teleport so we don't get kicked.
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(p.data));
                int teleportId = readVarInt(d);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream b = new DataOutputStream(baos);
                writeVarInt(b, teleportId);
                sendVarIntPacket(out, PLAY_C2S_ACCEPT_TELEPORTATION, baos.toByteArray());
                if (verbose) log("[play] PlayerPosition → ack teleport=" + teleportId);
            }
            case PLAY_S2C_START_CONFIGURATION -> {
                log("[play] StartConfiguration — server wants to re-enter CONFIG (unsupported)");
            }
            default -> {
                if (verbose && p.id != 30 /* CLIENTBOUND_LEVEL_PARTICLES is chatty */) {
                    // suppress most chatty packets
                    // log only a small set
                }
            }
        }
    }

    private static void decodeServuxLitematics(byte[] payload) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
            int type = readVarInt(d);
            log("[servux] inbound packet type=" + type);
            if (type == SERVUX_S2C_METADATA) {
                byte tagId = d.readByte();
                if (tagId == LitematicNbt.TAG_END) {
                    log("[servux] metadata: empty NBT");
                    return;
                }
                if (tagId != LitematicNbt.TAG_COMPOUND) {
                    log("[servux] metadata: unexpected tag id " + tagId);
                    return;
                }
                // Forge a named tag with empty name
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                sink.write(tagId);
                sink.write(0);
                sink.write(0);
                int b;
                while ((b = d.read()) != -1) sink.write(b);
                DataInputStream forged = new DataInputStream(new ByteArrayInputStream(sink.toByteArray()));
                LitematicNbt.NamedTag named = LitematicNbt.readNamedTag(forged);
                if (named.tag() instanceof LitematicNbt.NbtCompound c) {
                    log("[servux] metadata NBT keys: " + c.entries().keySet());
                    String name   = (c.entries().get("name")   instanceof LitematicNbt.NbtString s) ? s.value() : null;
                    String id     = (c.entries().get("id")     instanceof LitematicNbt.NbtString s) ? s.value() : null;
                    Integer ver   = (c.entries().get("version") instanceof LitematicNbt.NbtInt    i) ? i.value() : null;
                    String servux = (c.entries().get("servux") instanceof LitematicNbt.NbtString s) ? s.value() : null;
                    log("[servux] name=" + name + " id=" + id + " version=" + ver + " servux=" + servux);
                    if (wire >= 2) {
                        // Replicate the 26.2-0.28.5 client's hard checks
                        // (EntityDataManager.receiveServuxMetadata).
                        if ("litematic_data".equals(name)
                                && ver != null && ver == 2
                                && servux != null && servux.startsWith("servux-fabric-")) {
                            log("[servux] METADATA OK — v2 checks passed (version=2, servux prefix)");
                        } else {
                            log("[servux] METADATA REJECTED by v2 client rules — version=" + ver
                                    + " servux=" + servux);
                        }
                    } else if ("litematic_data".equals(name) && servux != null && servux.startsWith("LitematicaFolia")) {
                        log("[servux] METADATA OK — bridge active");
                    }
                }
            } else if (type == SERVUX_S2C_TASK_STATUS_SYNC) {
                LitematicNbt.NbtCompound sync = decodeDataTagBlob(d);
                if (sync != null) {
                    Byte complete = (sync.entries().get("InfoHudComplete") instanceof LitematicNbt.NbtByte nb)
                            ? nb.value() : null;
                    log("[servux] TASK_STATUS_SYNC InfoHudComplete=" + complete
                            + " keys=" + sync.entries().keySet());
                    if (complete != null && complete == (byte) 1) {
                        log("[servux] TASK COMPLETE SYNC OK");
                    }
                } else {
                    log("[servux] TASK_STATUS_SYNC with empty payload");
                }
            }
        } catch (Throwable t) {
            log("[servux] decode failed: " + t);
        }
    }

    // -------------------------------------------------------------- servux out
    private static void sendBrandCustomPayload(DataOutputStream out) throws IOException {
        sendCustomPayload(out, PLAY_C2S_CUSTOM_PAYLOAD, "minecraft:brand", brandPayload());
    }

    private static byte[] brandPayload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeString(d, "litematica-folia-bot");
        return b.toByteArray();
    }

    /**
     * Vanilla "REGISTER" channel — body is a NUL-separated list of channel
     * names. Paper uses this to allow inbound payloads on those channels.
     */
    private static void sendChannelRegister(DataOutputStream out) throws IOException {
        String channels = String.join("\0",
                CHANNEL_LITEMATICS,
                "servux:structures",
                "litematicafolia:easy_place");
        byte[] payload = channels.getBytes(StandardCharsets.UTF_8);
        sendCustomPayload(out, PLAY_C2S_CUSTOM_PAYLOAD, "minecraft:register", payload);
    }

    private static void sendServuxMetadataRequest(DataOutputStream out) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, SERVUX_C2S_METADATA_REQUEST);
        if (wire >= 2) {
            // 26.2-0.28.5 client shape: vanilla network NBT {version: Int 2}.
            LitematicNbt.NbtCompound req = new LitematicNbt.NbtCompound();
            req.putInt("version", 2);
            writeNbtCompoundAnonymous(d, req);
        } else {
            // Old-shape request: empty NBT compound — write TAG_END (0)
            d.writeByte(0);
        }
        sendCustomPayload(out, PLAY_C2S_CUSTOM_PAYLOAD, CHANNEL_LITEMATICS, b.toByteArray());
    }

    /**
     * Stream the schematic to the server using Servux's two-layer splitter
     * + Litematic-Transmit sub-protocol (see DirectPasteHandler javadoc).
     *
     * <p>The wire is uniformly type-13 (C2S_NBT_STREAM_DATA) packets. The
     * outer splitter glues N slices into one application payload; the
     * application payload is {@code VarInt(transactionId) + NBT(compound)};
     * the compound carries the Transmit sub-protocol frames (Start /
     * Data / End) keyed by a random {@code SliceKey} long.
     *
     * <p>We send exactly three application payloads:
     * <ol>
     *   <li>{@code Litematic-TransmitStart} — carries {@code SliceKey}
     *       and {@code PlacementData} (Origin*, PlaceEntities, …).</li>
     *   <li>{@code Litematic-TransmitData} — carries {@code SliceKey},
     *       {@code Size}, and the full gzipped {@code .litematic} bytes
     *       in a single {@code Data} byte-array. For larger files this
     *       would be split into multiple Data frames.</li>
     *   <li>{@code Litematic-TransmitEnd} — carries {@code SliceKey} and
     *       {@code TotalSize}.</li>
     * </ol>
     *
     * <p>Each application payload itself is then split into one or more
     * type-13 wire packets via the outer Servux splitter (first slice
     * prepended with VarInt(totalLen)).
     */
    private static void streamDirectPaste(DataOutputStream out, byte[] gzippedLitematic,
                                          int ox, int oy, int oz) throws IOException {
        long sliceKey = new java.util.Random().nextLong();

        // --- Frame 1: TransmitStart ---
        LitematicNbt.NbtCompound placementData = new LitematicNbt.NbtCompound();
        placementData.putInt("OriginX", ox);
        placementData.putInt("OriginY", oy);
        placementData.putInt("OriginZ", oz);
        placementData.putByte("PlaceEntities", (byte) 1);
        placementData.putByte("PlaceTileEntities", (byte) 1);
        placementData.putByte("PlacePendingTicks", (byte) 1);
        placementData.putByte("DeferredPhysics", (byte) 1);
        placementData.putByte("ObserversLast", (byte) 1);
        placementData.putInt("YawRotation", 0);

        LitematicNbt.NbtCompound startFrame = new LitematicNbt.NbtCompound();
        startFrame.putString("Task", "Litematic-TransmitStart");
        startFrame.putLong("SliceKey", sliceKey);
        startFrame.put("PlacementData", placementData);
        startFrame.putInt("TotalSize", gzippedLitematic.length);
        sendTransmitFrame(out, /* transactionId */ 1, startFrame);
        log("[servux] sent TransmitStart sliceKey=" + sliceKey + " totalSize=" + gzippedLitematic.length);

        // --- Frame 2: TransmitData (one big slice — fits in 16 MiB cap) ---
        LitematicNbt.NbtCompound dataFrame = new LitematicNbt.NbtCompound();
        dataFrame.putString("Task", "Litematic-TransmitData");
        dataFrame.putLong("SliceKey", sliceKey);
        dataFrame.putInt("Size", gzippedLitematic.length);
        dataFrame.putByteArray("Data", gzippedLitematic);
        sendTransmitFrame(out, 1, dataFrame);
        log("[servux] sent TransmitData (" + gzippedLitematic.length + " bytes)");

        // --- Frame 3: TransmitEnd ---
        LitematicNbt.NbtCompound endFrame = new LitematicNbt.NbtCompound();
        endFrame.putString("Task", "Litematic-TransmitEnd");
        endFrame.putLong("SliceKey", sliceKey);
        endFrame.putInt("TotalSize", gzippedLitematic.length);
        sendTransmitFrame(out, 1, endFrame);
        log("[servux] sent TransmitEnd");
    }

    /**
     * Serialise one Transmit-protocol frame as {@code VarInt(transactionId)
     * + NBT(frame)}, then run it through the outer splitter (chunking
     * into 16 KiB slices, first slice prepended with VarInt(totalLen)),
     * and ship each slice as a {@code PLAY_C2S_CUSTOM_PAYLOAD} on
     * {@code servux:litematics}.
     */
    private static void sendTransmitFrame(DataOutputStream out, int transactionId,
                                          LitematicNbt.NbtCompound frame) throws IOException {
        ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
        DataOutputStream payloadOut = new DataOutputStream(payloadBaos);
        writeVarInt(payloadOut, transactionId);
        writeNbtCompoundAnonymous(payloadOut, frame);
        byte[] payload = payloadBaos.toByteArray();

        // Outer splitter slicing — 16 KiB budget per slice. First slice
        // is prepended with VarInt(totalLen).
        final int SLICE_BUDGET = 16 * 1024;
        int offset = 0;
        boolean firstSlice = true;
        int frames = 0;
        while (offset < payload.length || firstSlice) {
            ByteArrayOutputStream slice = new ByteArrayOutputStream();
            DataOutputStream sliceOut = new DataOutputStream(slice);
            writeVarInt(sliceOut, SERVUX_C2S_NBT_STREAM_DATA);
            int sliceHdrLen = 0;
            if (firstSlice) {
                ByteArrayOutputStream hdr = new ByteArrayOutputStream();
                writeVarInt(new DataOutputStream(hdr), payload.length);
                sliceOut.write(hdr.toByteArray());
                sliceHdrLen = hdr.size();
                firstSlice = false;
            }
            int avail = payload.length - offset;
            int take = Math.min(avail, SLICE_BUDGET - sliceHdrLen - 8 /* a little margin */);
            if (take < 0) take = 0;
            if (take > 0) {
                sliceOut.write(payload, offset, take);
                offset += take;
            }
            sendCustomPayload(out, PLAY_C2S_CUSTOM_PAYLOAD, CHANNEL_LITEMATICS, slice.toByteArray());
            frames++;
            if (avail <= take) break;
        }
        if (verbose) log("[servux]   outer-splitter frame -> " + frames + " slice(s), " + payload.length + " body bytes");
    }

    /**
     * Wire-v2 (Litematica 26.2-0.28.5) Servux Paste: ONE application
     * payload — a "Data Tag" blob {@code Int32(len) + gzip(NBT file
     * stream, empty root name)} — carrying the whole placement compound
     * ({@code Task=LitematicaPaste}, {@code Schematics} = full .litematic
     * root compound inline), split into type-13 slices by the outer
     * splitter. No transactionId, no Transmit sub-protocol.
     */
    private static void streamDirectPasteV2(DataOutputStream out, byte[] gzippedLitematic,
                                            int ox, int oy, int oz) throws IOException {
        // Parse the .litematic (gzipped NBT file with a named root) into
        // a compound so it can ride inline under "Schematics".
        LitematicNbt.NbtCompound schematicRoot;
        try (DataInputStream dis = new DataInputStream(new java.util.zip.GZIPInputStream(
                new ByteArrayInputStream(gzippedLitematic)))) {
            LitematicNbt.NamedTag named = LitematicNbt.readNamedTag(dis);
            if (!(named.tag() instanceof LitematicNbt.NbtCompound c)) {
                throw new IOException("litematic root is not a compound");
            }
            schematicRoot = c;
        }
        log("[servux] parsed .litematic root keys: " + schematicRoot.entries().keySet());

        LitematicNbt.NbtCompound data = new LitematicNbt.NbtCompound();
        data.putString("Name", "protobot-paste");
        data.putString("HashCode", UUID.randomUUID().toString());
        data.put("Schematics", schematicRoot);
        data.putIntArray("Origin", new int[] {ox, oy, oz});
        data.putInt("Rotation", 0);   // v2: enum ORDINAL (NONE)
        data.putInt("Mirror", 0);     // v2: enum ORDINAL (NONE)
        data.put("SubRegions", new LitematicNbt.NbtCompound());
        data.putString("ReplaceMode", "NONE");
        data.putString("PasteLayerBehavior", "ALL");
        data.putString("Task", "LitematicaPaste");
        data.putInt("Interval", 1);

        byte[] blob = encodeDataTagBlob(data);
        log("[servux] v2 Data Tag blob: " + blob.length + " bytes (Int32 + gzip)");
        sendSplitterSlices(out, blob);
    }

    /** Data Tag blob = Int32(len) + gzip(NBT file stream, root name ""). */
    private static byte[] encodeDataTagBlob(LitematicNbt.NbtCompound c) throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(new java.util.zip.GZIPOutputStream(gz))) {
            LitematicNbt.writeNamedTag(dos, new LitematicNbt.NamedTag("", c));
        }
        byte[] body = gz.toByteArray();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(body.length + 4);
        DataOutputStream dos = new DataOutputStream(outBytes);
        dos.writeInt(body.length);
        dos.write(body);
        dos.flush();
        return outBytes.toByteArray();
    }

    /** Decode a Data Tag blob (Int32 + gzip NBT file stream) or null. */
    private static LitematicNbt.NbtCompound decodeDataTagBlob(DataInputStream d) throws IOException {
        int len = d.readInt();
        byte[] body = new byte[len];
        d.readFully(body);
        try (DataInputStream dis = new DataInputStream(new java.util.zip.GZIPInputStream(
                new ByteArrayInputStream(body)))) {
            if (dis.read() != LitematicNbt.TAG_COMPOUND) {
                return null;
            }
            // Re-frame: id + rest (name + payload) for readNamedTag.
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            rest.write(LitematicNbt.TAG_COMPOUND);
            int b;
            while ((b = dis.read()) != -1) rest.write(b);
            LitematicNbt.NamedTag named = LitematicNbt.readNamedTag(
                    new DataInputStream(new ByteArrayInputStream(rest.toByteArray())));
            return named.tag() instanceof LitematicNbt.NbtCompound c ? c : null;
        }
    }

    /**
     * Chunk one application payload into type-13 wire packets via the
     * outer splitter (first slice prefixed with VarInt(totalLen)).
     */
    private static void sendSplitterSlices(DataOutputStream out, byte[] payload) throws IOException {
        final int SLICE_BUDGET = 16 * 1024;
        int offset = 0;
        boolean firstSlice = true;
        int frames = 0;
        while (offset < payload.length || firstSlice) {
            ByteArrayOutputStream slice = new ByteArrayOutputStream();
            DataOutputStream sliceOut = new DataOutputStream(slice);
            writeVarInt(sliceOut, SERVUX_C2S_NBT_STREAM_DATA);
            int sliceHdrLen = 0;
            if (firstSlice) {
                ByteArrayOutputStream hdr = new ByteArrayOutputStream();
                writeVarInt(new DataOutputStream(hdr), payload.length);
                sliceOut.write(hdr.toByteArray());
                sliceHdrLen = hdr.size();
                firstSlice = false;
            }
            int avail = payload.length - offset;
            int take = Math.min(avail, SLICE_BUDGET - sliceHdrLen - 8);
            if (take < 0) take = 0;
            if (take > 0) {
                sliceOut.write(payload, offset, take);
                offset += take;
            }
            sendCustomPayload(out, PLAY_C2S_CUSTOM_PAYLOAD, CHANNEL_LITEMATICS, slice.toByteArray());
            frames++;
            if (avail <= take) break;
        }
        if (verbose) log("[servux]   splitter -> " + frames + " slice(s), " + payload.length + " body bytes");
    }

    private static void writeNbtCompoundAnonymous(DataOutputStream out, LitematicNbt.NbtCompound c) throws IOException {
        // Anonymous-root NBT (1.20.2+ network form): TAG_COMPOUND byte then payload.
        // Internal LitematicNbt only knows how to write NamedTag (id + utfName + payload).
        // We write to a sink, then strip the 2 UTF-length bytes between id and payload.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DataOutputStream sinkOut = new DataOutputStream(sink);
        LitematicNbt.writeNamedTag(sinkOut, new LitematicNbt.NamedTag("", c));
        byte[] full = sink.toByteArray();
        if (full.length < 3) throw new IOException("nbt encode underflow");
        out.write(full, 0, 1);              // tag id
        out.write(full, 3, full.length - 3); // payload (skip 2-byte UTF len)
    }

    private static void sendCustomPayload(DataOutputStream out, int packetId, String channel, byte[] payload)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream b = new DataOutputStream(baos);
        writeString(b, channel);
        b.write(payload);
        sendVarIntPacket(out, packetId, baos.toByteArray());
    }

    // -------------------------------------------------------------- framing
    private static void sendVarIntPacket(DataOutputStream out, int packetId, byte[] body) throws IOException {
        // packet = VarInt(length) | VarInt(packetId) | body
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(pkt);
        writeVarInt(p, packetId);
        p.write(body);
        byte[] pktBytes = pkt.toByteArray();
        writeVarInt(out, pktBytes.length);
        out.write(pktBytes);
        out.flush();
    }

    record Packet(int id, byte[] data) {}

    private static Packet readPacket(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len <= 0) return new Packet(-1, new byte[0]);
        byte[] buf = new byte[len];
        in.readFully(buf);
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(buf));
        int id = readVarInt(d);
        byte[] body = new byte[d.available()];
        d.readFully(body);
        return new Packet(id, body);
    }

    // -------------------------------------------------------------- low-level
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bs = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bs.length);
        out.write(bs);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
            if (shift >= 35) throw new IOException("VarInt too long");
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        return v;
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        byte[] bs = new byte[len];
        in.readFully(bs);
        return new String(bs, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(DataInputStream d) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = d.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    // -------------------------------------------------------------- args
    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> {
                    String t = args[++i];
                    int colon = t.indexOf(':');
                    host = t.substring(0, colon);
                    port = Integer.parseInt(t.substring(colon + 1));
                }
                case "--litematic" -> litematicPath = Path.of(args[++i]);
                case "--paste-origin" -> {
                    String[] parts = args[++i].split(",");
                    originX = Integer.parseInt(parts[0]);
                    originY = Integer.parseInt(parts[1]);
                    originZ = Integer.parseInt(parts[2]);
                }
                case "--username" -> username = args[++i];
                case "--hold-seconds" -> holdSeconds = Integer.parseInt(args[++i]);
                case "--quiet" -> verbose = false;
                case "--wire" -> {
                    String w = args[++i];
                    wire = w.equals("v2") || w.equals("2") ? 2 : 1;
                }
                default -> System.err.println("unknown arg: " + args[i]);
            }
        }
        if (litematicPath == null) fail("missing --litematic <path>");
    }

    private static void fail(String msg) {
        System.err.println("[bot] FATAL: " + msg);
        System.exit(2);
    }

    private static void log(String msg) {
        System.err.println("[bot] " + msg);
    }
}
