package net.flanwarfare.designer.client;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CCustomPayloadPacket;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Network side of the Flag Designer on the client. The server decides everything.
 *
 * <p>Wire format of Save Flag (client -> server, channel {@code flanwarfare:designer}), read by
 * Flan Warfare's {@code FlagDesigner.onPayload} with exactly the same limits:
 * <pre>
 *   UUID    claimId
 *   long    token
 *   varint  base dye id (0..15)
 *   varint  layer count (0..MAX_WIRE_LAYERS)
 *   count x { string pattern id (max MAX_ID_LENGTH chars), varint dye id }
 * </pre>
 * Pattern ids are the banner NBT ids ({@code BannerPattern.getHashname()}): stable across both
 * sides even when mods add patterns, unlike enum ordinals.
 */
public final class DesignerClient {
    private static final ResourceLocation TO_SERVER = new ResourceLocation("flanwarfare", "designer");
    /** Same value as Flan Warfare's DesignRules.MAX_ID_LENGTH (the mods do not share classes). */
    static final int MAX_ID_LENGTH = 64;
    static final int MAX_WIRE_LAYERS = 16;

    private DesignerClient() {
    }

    /** Server -> client: type 1 = save result (ok, message). */
    public static void onPayload(NetworkEvent.ServerCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        try {
            PacketBuffer buf = event.getPayload();
            int type = buf.readByte();
            if (type == 1) {
                boolean ok = buf.readBoolean();
                String message = buf.readString(256);
                ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().currentScreen instanceof FlagDesignerScreen) {
                        ((FlagDesignerScreen) Minecraft.getInstance().currentScreen).onResult(ok, message);
                    }
                });
            }
        } catch (RuntimeException malformed) {
            // A malformed message must never crash the client; the server will time out the save.
        }
        ctx.setPacketHandled(true);
    }

    /**
     * Save Flag: sends the edited design. Returns null when sent, otherwise why it could not be
     * sent (the design is then kept open). Never throws: every string is checked against the
     * same limit the server reads with before anything is written.
     */
    static String sendSave(UUID claimId, long token, int base, List<String> hashes, List<Integer> colors) {
        if (hashes.size() != colors.size() || hashes.size() > MAX_WIRE_LAYERS) {
            return "Too many pattern layers.";
        }
        for (String h : hashes) {
            if (h == null || h.isEmpty() || h.length() > MAX_ID_LENGTH
                    || h.getBytes(StandardCharsets.UTF_8).length > MAX_ID_LENGTH * 4) {
                return "A pattern from an installed mod has an id the designer cannot send (" + h + ").";
            }
        }
        if (Minecraft.getInstance().getConnection() == null) {
            return "Not connected to a server.";
        }
        try {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            buf.writeUniqueId(claimId);
            buf.writeLong(token);
            buf.writeVarInt(base);
            buf.writeVarInt(hashes.size());
            for (int i = 0; i < hashes.size(); i++) {
                buf.writeString(hashes.get(i), MAX_ID_LENGTH * 4);
                buf.writeVarInt(colors.get(i));
            }
            Minecraft.getInstance().getConnection().sendPacket(new CCustomPayloadPacket(TO_SERVER, buf));
            return null;
        } catch (EncoderException | IllegalArgumentException e) {
            return "Could not send the design: " + e.getMessage();
        }
    }
}
