package net.flanwarfare.flags.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CCustomPayloadPacket;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Network side of the Flag Designer on the client. The server decides everything. */
public final class DesignerClient {
    private static final ResourceLocation TO_SERVER = new ResourceLocation("flanwarfare", "designer");

    private DesignerClient() {
    }

    public static void onPayload(NetworkEvent.ServerCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        PacketBuffer buf = event.getPayload();
        int type = buf.readByte();
        if (type == 0) {
            UUID claimId = buf.readUniqueId();
            long token = buf.readLong();
            String label = buf.readString(64);
            CompoundNBT design = buf.readCompoundTag();
            int n = buf.readVarInt();
            List<String> unlocked = new ArrayList<>();
            for (int i = 0; i < n && i < 16; i++) {
                unlocked.add(buf.readString(16));
            }
            int maxLayers = buf.readVarInt();
            ctx.enqueueWork(() -> Minecraft.getInstance().displayGuiScreen(
                    new FlagDesignerScreen(claimId, token, label, design, unlocked, maxLayers)));
        } else if (type == 1) {
            boolean ok = buf.readBoolean();
            String message = buf.readString(256);
            ctx.enqueueWork(() -> {
                if (Minecraft.getInstance().currentScreen instanceof FlagDesignerScreen) {
                    ((FlagDesignerScreen) Minecraft.getInstance().currentScreen).onResult(ok, message);
                }
            });
        }
        ctx.setPacketHandled(true);
    }

    /** Save Flag: the edited design goes to the server, which validates and applies it. */
    static void sendSave(UUID claimId, long token, int base, List<String> hashes, List<Integer> colors) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeUniqueId(claimId);
        buf.writeLong(token);
        buf.writeVarInt(base);
        buf.writeVarInt(hashes.size());
        for (int i = 0; i < hashes.size(); i++) {
            buf.writeString(hashes.get(i), 16);
            buf.writeVarInt(colors.get(i));
        }
        if (Minecraft.getInstance().getConnection() != null) {
            Minecraft.getInstance().getConnection().sendPacket(new CCustomPayloadPacket(TO_SERVER, buf));
        }
    }
}
