package fr.ekaii.litematica.protocol.easyplace;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-connection Netty inbound handler, inserted right before Minecraft's
 * {@code packet_handler}, that recognises Litematica Easy Place V3 clicks
 * in {@code ServerboundUseItemOnPacket}.
 *
 * <p>Servux achieves the same with a mixin that neutralises the
 * "cursor must be inside the clicked block" check in
 * {@code ServerGamePacketListenerImpl#handleUseItemOn}
 * ({@code |hit - blockCenter| < 1.0000001} on every axis), which any
 * V3-encoded cursor ({@code x >= 2}) fails. A plugin cannot mixin, so this
 * handler:
 * <ol>
 *   <li>decodes the protocol value from the relative cursor X,</li>
 *   <li>memoises it under {@code (player, clicked block)} for the Bukkit
 *       {@code BlockPlaceEvent} stage, and</li>
 *   <li>hands the pipeline a rewritten packet whose cursor X carries only
 *       the original raycast fraction, so vanilla accepts and performs the
 *       placement.</li>
 * </ol>
 *
 * <p>Runs on the connection's event loop. It never touches the world or
 * the Bukkit API; the only shared state is the owner's concurrent memo
 * map. Any unexpected failure forwards the original packet untouched, so
 * a bug here can degrade to vanilla behaviour but never break the
 * connection. Vanilla clients always send {@code 0 <= x <= 1}, which
 * decodes to "no protocol value" and passes through without allocation.
 */
final class EasyPlaceChannelHandler extends ChannelInboundHandlerAdapter {

    /** Pipeline handler name. */
    static final String NAME = "litematica_easyplace";

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/EasyPlace");

    private final EasyPlaceListener owner;
    private final UUID playerId;
    private final String playerName;
    private volatile boolean warnedOnce;

    EasyPlaceChannelHandler(EasyPlaceListener owner, UUID playerId, String playerName) {
        this.owner = owner;
        this.playerId = playerId;
        this.playerName = playerName;
    }

    UUID playerId() {
        return playerId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Object forward = msg;
        if (msg instanceof ServerboundUseItemOnPacket packet) {
            try {
                forward = intercept(packet);
            } catch (Throwable t) {
                if (!warnedOnce) {
                    warnedOnce = true;
                    LOG.log(Level.WARNING, "easy-place intercept failed for " + playerName
                            + "; forwarding the original packet (further failures are silent)", t);
                }
                forward = msg;
            }
        }
        super.channelRead(ctx, forward);
    }

    private Object intercept(ServerboundUseItemOnPacket packet) {
        BlockHitResult hit = packet.hitResult();
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return packet;
        }
        BlockPos pos = hit.getBlockPos();
        Vec3 location = hit.getLocation();
        double relX = location.x - pos.getX();
        int protocolValue = EasyPlaceProtocolDecoder.decodeProtocolValue(relX);
        if (protocolValue < 0) {
            return packet; // vanilla click
        }

        owner.remember(playerId, pos, hit.getDirection(), protocolValue);

        Vec3 sane = new Vec3(pos.getX() + EasyPlaceProtocolDecoder.originalFraction(relX),
                location.y, location.z);
        BlockHitResult rewritten = new BlockHitResult(sane, hit.getDirection(), pos,
                hit.isInside(), hit.isWorldBorderHit());
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("easy-place decoded: player=" + playerName + " pos=" + pos.toShortString()
                    + " face=" + hit.getDirection() + " protocolValue=0x"
                    + Integer.toHexString(protocolValue));
        }
        return new ServerboundUseItemOnPacket(packet.hand(), rewritten, packet.sequence(), packet.timestamp());
    }
}
