package net.grandtab;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.play.client.CResourcePackStatusPacket;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Watches the client's answer to the server resource pack (accepted, loaded, declined, failed)
 * without changing anything: the packet always continues to Minecraft's own handler.
 *
 * <p>The status is a private enum field of the packet. It is found by its type rather than by
 * name, so it works with any mapping (development or the obfuscated production names).
 */
final class PackStatusListener extends ChannelInboundHandlerAdapter {
    static final String NAME = "grandtab_pack_status";
    private static volatile Field statusField;
    private static volatile boolean lookedUp;

    private final TabService service;
    private final UUID player;

    PackStatusListener(TabService service, UUID player) {
        this.service = service;
        this.player = player;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof CResourcePackStatusPacket) {
            try {
                String status = status((CResourcePackStatusPacket) msg);
                if (status != null) {
                    service.packStatus(player, status);
                }
            } catch (RuntimeException | IllegalAccessException e) {
                GrandTab.LOG.debug("Grand Tab: could not read resource pack status", e);
            }
        }
        super.channelRead(ctx, msg);
    }

    private static String status(CResourcePackStatusPacket packet) throws IllegalAccessException {
        if (!lookedUp) {
            synchronized (PackStatusListener.class) {
                if (!lookedUp) {
                    for (Field f : CResourcePackStatusPacket.class.getDeclaredFields()) {
                        if (f.getType().isEnum()) {
                            f.setAccessible(true);
                            statusField = f;
                            break;
                        }
                    }
                    lookedUp = true;
                    if (statusField == null) {
                        GrandTab.LOG.warn("Grand Tab: resource pack status field not found; logo mode AUTO will show the text title");
                    }
                }
            }
        }
        Field f = statusField;
        if (f == null) {
            return null;
        }
        Object value = f.get(packet);
        return value instanceof Enum ? ((Enum<?>) value).name() : null;
    }
}
