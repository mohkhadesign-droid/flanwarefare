package net.flanwarfare.flag;

import io.github.flemmli97.flan.claim.Claim;
import io.netty.buffer.Unpooled;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.DesignRules;
import net.flanwarfare.util.Msg;
import net.minecraft.block.BannerBlock;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.BannerPatternItem;
import net.minecraft.item.DyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SCustomPayloadPlayPacket;
import net.minecraft.tileentity.BannerPattern;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.event.EventNetworkChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server side of the Flag Designer (the screen itself is in the Flan Warfare Flags client mod).
 *
 * <ol>
 *   <li>Left-click on any block of a claim's flag (pole, model column or banner): the click is
 *   cancelled (never mines or breaks anything) and, if the clicker owns that claim in Flan
 *   (or is an operator with {@code flags.designer_admin_override}), the client gets an
 *   <i>open</i> message on {@code flanwarfareflags:designer} with the claim's current design,
 *   the item-locked patterns the player may use, and a one-time edit token.</li>
 *   <li>Save Flag: the client sends the design on {@code flanwarfare:designer}. Everything is
 *   re-checked on the server: the token, that the same claim still exists, that its flag is
 *   still at the same place, that the sender still owns it, and every colour, pattern id, the
 *   layer limit and item-locked patterns ({@link DesignRules}). Only then is the claim's own
 *   flag design replaced, through the normal flag path (so the world banner, the model and the
 *   capture HUD all update for everyone nearby, and it persists like any flag design).</li>
 *   <li>The client gets a result message: success closes the designer, failure keeps the
 *   player's unsaved work open with the reason.</li>
 * </ol>
 */
public final class FlagDesigner {
    private static final ResourceLocation TO_CLIENT = new ResourceLocation("flanwarfareflags", "designer");
    private static final ResourceLocation TO_SERVER = new ResourceLocation(FlanWarfare.MOD_ID, "designer");
    private static final long SESSION_MS = 15 * 60 * 1000L;
    private static EventNetworkChannel channel;

    private static final class Session {
        final UUID claimId;
        final String dimension;
        final BlockPos flagPos;
        final long token;
        final long expires;

        Session(UUID claimId, String dimension, BlockPos flagPos, long token, long expires) {
            this.claimId = claimId;
            this.dimension = dimension;
            this.flagPos = flagPos;
            this.token = token;
            this.expires = expires;
        }
    }

    private final WarCore core;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    public FlagDesigner(WarCore core) {
        this.core = core;
    }

    /**
     * Registers the client-to-server channel. Called from the mod constructor. Clients without
     * the Flags mod are accepted (the channel is optional for them).
     */
    public static void registerChannel() {
        channel = NetworkRegistry.newEventChannel(TO_SERVER, () -> "1", v -> true, v -> true);
        channel.addListener(FlagDesigner::onPayload);
    }

    public static boolean available() {
        return ModList.get().isLoaded("flanwarfareflags");
    }

    // ------------------------------------------------------------------ opening

    /** Left-click on a block. Flag blocks: cancel the click, open the designer for the owner. */
    public void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        String dimension = FlanBridge.dimensionId(player.world);
        UUID claimId = core.flags().claimOfFlagBlock(dimension, event.getPos());
        if (claimId == null) {
            return;
        }
        event.setCanceled(true); // never mine or break any part of a flag by clicking it
        if (!WarConfig.DESIGNER_ENABLED.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastOpen.get(player.getUniqueID());
        if (last != null && now - last < 1000L) {
            return;
        }
        lastOpen.put(player.getUniqueID(), now);
        ClaimRecord record = core.data().claim(claimId);
        Claim claim = record == null ? null : FlanBridge.find(core.server(), record);
        if (record == null || claim == null || !record.hasFlag || claim.isAdminClaim()) {
            return;
        }
        if (!mayEdit(player, claim)) {
            Msg.send(player, Msg.error("Only the claim owner can edit this flag."));
            return;
        }
        if (!available()) {
            Msg.send(player, Msg.info("The Flag Designer needs the Flan Warfare Flags mod. Meanwhile: hold a banner, "
                    + "/flag design set, then /flag design apply."));
            return;
        }
        Session session = new Session(claimId, dimension, record.flagPos(), ThreadLocalRandom.current().nextLong(),
                now + SESSION_MS);
        sessions.put(player.getUniqueID(), session);
        sendOpen(player, record, claim, session);
    }

    private boolean mayEdit(ServerPlayerEntity player, Claim claim) {
        if (player.getUniqueID().equals(claim.getOwner())) {
            return true;
        }
        return WarConfig.DESIGNER_ADMIN_OVERRIDE.get() && player.hasPermissionLevel(Msg.adminLevel());
    }

    /** Item-locked pattern ids the player may use: from items they carry. */
    private static Set<String> carriedItemPatterns(ServerPlayerEntity player) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack.getItem() instanceof BannerPatternItem) {
                set.add(((BannerPatternItem) stack.getItem()).getBannerPattern().getHashname());
            }
        }
        return set;
    }

    private static Set<String> currentPatterns(ItemStack design) {
        Set<String> set = new HashSet<>();
        CompoundNBT entity = design.getChildTag("BlockEntityTag");
        if (entity != null && entity.contains("Patterns", 9)) {
            ListNBT list = entity.getList("Patterns", 10);
            for (int i = 0; i < list.size(); i++) {
                set.add(list.getCompound(i).getString("Pattern"));
            }
        }
        return set;
    }

    private void sendOpen(ServerPlayerEntity player, ClaimRecord record, Claim claim, Session session) {
        ItemStack design = BannerDesigns.fromSnbt(record.designSnbt);
        Set<String> unlocked = carriedItemPatterns(player);
        unlocked.addAll(currentPatterns(design));
        unlocked.retainAll(DesignRules.ITEM_PATTERNS);
        CompoundNBT data = new CompoundNBT();
        data.putInt("Base", BannerDesigns.color(design).getId());
        CompoundNBT entity = design.getChildTag("BlockEntityTag");
        data.put("Patterns", entity != null && entity.contains("Patterns", 9) ? entity.getList("Patterns", 10).copy() : new ListNBT());
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(0);
        buf.writeUniqueId(session.claimId);
        buf.writeLong(session.token);
        buf.writeString(FlanBridge.label(claim), 64);
        buf.writeCompoundTag(data);
        buf.writeVarInt(unlocked.size());
        for (String h : unlocked) {
            buf.writeString(h, 16);
        }
        buf.writeVarInt(DesignRules.MAX_LAYERS);
        player.connection.sendPacket(new SCustomPayloadPlayPacket(TO_CLIENT, buf));
    }

    private static void sendResult(ServerPlayerEntity player, boolean ok, String message) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(1);
        buf.writeBoolean(ok);
        buf.writeString(message, 256);
        player.connection.sendPacket(new SCustomPayloadPlayPacket(TO_CLIENT, buf));
    }

    // ------------------------------------------------------------------ saving

    private static void onPayload(NetworkEvent.ClientCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        ServerPlayerEntity player = ctx.getSender();
        try {
            PacketBuffer buf = event.getPayload();
            UUID claimId = buf.readUniqueId();
            long token = buf.readLong();
            int base = buf.readVarInt();
            int count = buf.readVarInt();
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("layer count " + count);
            }
            List<String> hashes = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                hashes.add(buf.readString(16));
                colors.add(buf.readVarInt());
            }
            ctx.enqueueWork(() -> {
                WarCore core = WarCore.ready();
                if (core != null && player != null && core.designer() != null) {
                    core.designer().save(player, claimId, token, base, hashes, colors);
                }
            });
        } catch (RuntimeException malformed) {
            FlanWarfare.LOG.warn("Malformed Flag Designer packet from {}: {}",
                    player == null ? "?" : player.getGameProfile().getName(), malformed.toString());
        }
        ctx.setPacketHandled(true);
    }

    void save(ServerPlayerEntity player, UUID claimId, long token, int base, List<String> hashes, List<Integer> colors) {
        Session session = sessions.get(player.getUniqueID());
        if (session == null || !session.claimId.equals(claimId) || session.token != token
                || System.currentTimeMillis() > session.expires) {
            sendResult(player, false, "This editor session expired. Left-click the flag again.");
            return;
        }
        ClaimRecord record = core.data().claim(claimId);
        Claim claim = record == null ? null : FlanBridge.find(core.server(), record);
        if (record == null || claim == null || !record.hasFlag) {
            sessions.remove(player.getUniqueID());
            sendResult(player, false, "This claim or its flag no longer exists.");
            return;
        }
        if (!record.dimension.equals(session.dimension) || !record.flagPos().equals(session.flagPos)) {
            sessions.remove(player.getUniqueID());
            sendResult(player, false, "The flag was moved. Left-click it again at its new place.");
            return;
        }
        if (!mayEdit(player, claim)) {
            sessions.remove(player.getUniqueID());
            sendResult(player, false, "Only the claim owner can edit this flag.");
            return;
        }
        Set<String> known = new HashSet<>();
        for (BannerPattern p : BannerPattern.values()) {
            known.add(p.getHashname());
        }
        Set<String> unlocked = carriedItemPatterns(player);
        unlocked.addAll(currentPatterns(BannerDesigns.fromSnbt(record.designSnbt)));
        String problem = DesignRules.check(base, hashes, colors, known, unlocked);
        if (problem != null) {
            sendResult(player, false, problem);
            return;
        }
        ItemStack banner = new ItemStack(BannerBlock.forColor(DyeColor.byId(base)));
        if (!hashes.isEmpty()) {
            ListNBT patterns = new ListNBT();
            for (int i = 0; i < hashes.size(); i++) {
                CompoundNBT layer = new CompoundNBT();
                layer.putString("Pattern", hashes.get(i));
                layer.putInt("Color", colors.get(i));
                patterns.add(layer);
            }
            banner.getOrCreateChildTag("BlockEntityTag").put("Patterns", patterns);
        }
        boolean visible = core.flags().setClaimDesign(record, BannerDesigns.toSnbt(banner));
        sessions.remove(player.getUniqueID());
        core.audit("{} saved a new flag design for claim {} ({} layers)", player.getGameProfile().getName(), claimId,
                hashes.size());
        sendResult(player, true, visible ? "Flag design saved." : "Flag design saved. It will show once the current capture attempt ends.");
    }

    public void onLogout(UUID player) {
        sessions.remove(player);
        lastOpen.remove(player);
    }
}
