package net.flanwarfare.flag;

import io.github.flemmli97.flan.claim.Claim;
import io.netty.buffer.Unpooled;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.inventory.container.SimpleNamedContainerProvider;
import net.minecraft.item.Item;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.DesignRules;
import net.flanwarfare.util.Msg;
import net.minecraft.block.BannerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.world.server.ServerWorld;
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
 * Server side of the Flag Designer (the block, menu and screen are in the separate, optional
 * Flan Warfare Designer mod, {@code flanwarfaredesigner}).
 *
 * <ol>
 *   <li>Right-click on a Flag Designer block: the server finds the claim the block stands in
 *   and, if the clicker may edit that claim's flag ({@code designer_station_access}; the owner
 *   by default, or an operator with {@code flags.designer_admin_override}), opens the
 *   Designer mod's container {@code flanwarfaredesigner:designer} (a real menu with the Banner
 *   Pattern Item slot) and sends the claim, a one-time edit token and the current design as
 *   its extra data.</li>
 *   <li>Save Flag: the client sends the design on {@code flanwarfare:designer}. Everything is
 *   re-checked on the server: the token, that the same claim still exists, that its flag is
 *   still at the same place, that the sender still owns it, and every colour, pattern id, the
 *   layer limit and item-locked patterns ({@link DesignRules}): an item-locked pattern needs
 *   its banner pattern item in the open designer's slot <i>on the server</i>, or must already
 *   be on this flag. Only then is the claim's own
 *   flag design replaced, through the normal flag path (so the world banner, the model and the
 *   capture HUD all update for everyone nearby, and it persists like any flag design).</li>
 *   <li>The client gets a result message: success closes the designer, failure keeps the
 *   player's unsaved work open with the reason.</li>
 * </ol>
 */
public final class FlagDesigner {
    private static final ResourceLocation TO_CLIENT = new ResourceLocation("flanwarfaredesigner", "designer");
    private static final ResourceLocation TO_SERVER = new ResourceLocation(FlanWarfare.MOD_ID, "designer");
    private static final ResourceLocation CONTAINER_ID = new ResourceLocation("flanwarfaredesigner", "designer");
    private static final long SESSION_MS = 15 * 60 * 1000L;
    private static final int MAX_WIRE_LAYERS = 16;
    private static EventNetworkChannel channel;

    private static final ResourceLocation STATION_ID = new ResourceLocation("flanwarfaredesigner", "flag_designer");
    /** Saving from a station needs the player within this distance of it (blocks). */
    private static final double STATION_REACH = 8.0D;

    private static final class Session {
        final UUID claimId;
        final String dimension;
        final BlockPos flagPos;
        final long token;
        final long expires;
        /** Station key ("dimension|pos") if opened from a Flag Designer station, else null. */
        final String station;

        Session(UUID claimId, String dimension, BlockPos flagPos, long token, long expires, String station) {
            this.claimId = claimId;
            this.dimension = dimension;
            this.flagPos = flagPos;
            this.token = token;
            this.expires = expires;
            this.station = station;
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
        return ModList.get().isLoaded("flanwarfaredesigner") && designerType() != null;
    }

    /** The Designer mod's menu type (null if Flan Warfare Designer is not installed). */
    static ContainerType<?> designerType() {
        return ForgeRegistries.CONTAINERS.getValue(CONTAINER_ID);
    }

    /** The Flag Designer item (null if Flan Warfare Designer is not installed). */
    public static Item stationItem() {
        Item item = ForgeRegistries.ITEMS.getValue(STATION_ID);
        return item == null || item == net.minecraft.item.Items.AIR ? null : item;
    }

    private static boolean isStation(BlockState state) {
        ResourceLocation name = state.getBlock().getRegistryName();
        return STATION_ID.equals(name);
    }

    private static String stationKey(String dimension, BlockPos pos) {
        return dimension + "|" + pos.toLong();
    }

    private static BlockPos stationPos(String key) {
        return BlockPos.fromLong(Long.parseLong(key.substring(key.lastIndexOf('|') + 1)));
    }

    private static String stationDimension(String key) {
        return key.substring(0, key.lastIndexOf('|'));
    }

    /** True if this player has the designer menu open right now (server view). */
    private static boolean designerOpen(ServerPlayerEntity player) {
        ContainerType<?> type = designerType();
        Container open = player.openContainer;
        return type != null && open != null && open != player.container && open.getType() == type;
    }

    /**
     * Every pattern that needs a banner pattern item: the patterns applied by any registered
     * {@link BannerPatternItem} (vanilla's six and those of installed mods). Pattern id -> item.
     */
    public static Map<String, Item> itemLockedPatterns() {
        Map<String, Item> map = new HashMap<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (item instanceof BannerPatternItem) {
                map.putIfAbsent(((BannerPatternItem) item).getBannerPattern().getHashname(), item);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------ clicks

    /**
     * Left-click on a flag block opens the designer only when
     * {@code designer_open_on_flag_left_click} is on; otherwise the click is left alone (the
     * flag is protected by the normal break rules, as before the designer existed).
     */
    public void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity) || !WarConfig.DESIGNER_LEFT_CLICK.get()) {
            return;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        UUID claimId = core.flags().claimOfFlagBlock(FlanBridge.dimensionId(player.world), event.getPos());
        if (claimId == null) {
            return;
        }
        event.setCanceled(true); // never mine or break any part of a flag by clicking it
        open(player, claimId, null);
    }

    /**
     * Right-click on a Flag Designer block opens the designer for the flag of the claim the
     * block stands in. Everything else is left alone.
     */
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        BlockPos pos = event.getPos();
        if (!isStation(player.world.getBlockState(pos))) {
            return;
        }
        if (player.isSneaking() && !player.getHeldItem(event.getHand()).isEmpty()) {
            return; // sneaking with an item: let the player place blocks against it as usual
        }
        event.setCanceled(true);
        event.setCancellationResult(ActionResultType.SUCCESS);
        if (event.getHand() == Hand.MAIN_HAND) {
            useStation(player, player.getServerWorld(), pos);
        }
    }

    private boolean isAdmin(ServerPlayerEntity player) {
        return player.hasPermissionLevel(Msg.adminLevel());
    }

    /** The claim (parent) a designer block stands in, if it has a conquest flag; else null with a message. */
    private ClaimRecord stationClaim(ServerPlayerEntity player, ServerWorld world, BlockPos pos, boolean tell) {
        Claim claim = FlanBridge.parentAt(world, pos);
        if (claim == null) {
            if (tell) {
                Msg.send(player, Msg.info("Place the Flag Designer inside your claim: it designs the flag of the claim it stands in."));
            }
            return null;
        }
        if (claim.isAdminClaim() || core.isExcluded(claim)) {
            if (tell) {
                Msg.send(player, Msg.info("This claim has no conquest flag to design."));
            }
            return null;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        if (record == null || !record.hasFlag) {
            if (tell) {
                Msg.send(player, Msg.info("This claim has no flag yet. Place it first with /flag place."));
            }
            return null;
        }
        return record;
    }

    private void useStation(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        ClaimRecord record = stationClaim(player, world, pos, true);
        if (record != null) {
            open(player, record.claimId, stationKey(FlanBridge.dimensionId(world), pos));
        }
    }

    // ------------------------------------------------------------------ opening

    private void open(ServerPlayerEntity player, UUID claimId, String station) {
        if (!WarConfig.DESIGNER_ENABLED.get()) {
            Msg.send(player, Msg.info("The Flag Designer is disabled on this server."));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastOpen.get(player.getUniqueID());
        if (last != null && now - last < 500L) {
            return;
        }
        lastOpen.put(player.getUniqueID(), now);
        ClaimRecord record = core.data().claim(claimId);
        Claim claim = record == null ? null : FlanBridge.find(core.server(), record);
        if (record == null || claim == null || !record.hasFlag || claim.isAdminClaim()) {
            if (station != null) {
                Msg.send(player, Msg.error("The flag this Flag Designer is linked to no longer exists. An admin can link it again."));
            }
            return;
        }
        if (!mayUse(player, claim, station != null)) {
            Msg.send(player, Msg.error(station != null && "MEMBERS".equalsIgnoreCase(WarConfig.DESIGNER_STATION_ACCESS.get())
                    ? "Only the claim owner and its members can edit this flag." : "Only the claim owner can edit this flag."));
            return;
        }
        if (!available()) {
            Msg.send(player, Msg.info("The Flag Designer needs the Flan Warfare Flags mod (1.1 or newer). Meanwhile: "
                    + "hold a banner, /flag design set, then /flag design apply."));
            return;
        }
        Session session = new Session(claimId, record.dimension, record.flagPos(), ThreadLocalRandom.current().nextLong(),
                now + SESSION_MS, station);
        sessions.put(player.getUniqueID(), session);
        sendOpen(player, record, claim, session);
    }

    /** Who may edit: the owner (plus the admin override); stations follow designer_station_access. */
    private boolean mayUse(ServerPlayerEntity player, Claim claim, boolean fromStation) {
        if (player.getUniqueID().equals(claim.getOwner())) {
            return true;
        }
        if (WarConfig.DESIGNER_ADMIN_OVERRIDE.get() && isAdmin(player)) {
            return true;
        }
        if (!fromStation) {
            return false;
        }
        String access = WarConfig.DESIGNER_STATION_ACCESS.get();
        if ("ANYONE".equalsIgnoreCase(access)) {
            return true;
        }
        return "MEMBERS".equalsIgnoreCase(access) && FlanBridge.isMemberAnywhere(claim, player.getUniqueID());
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
        CompoundNBT data = new CompoundNBT();
        data.putInt("Base", BannerDesigns.color(design).getId());
        CompoundNBT entity = design.getChildTag("BlockEntityTag");
        data.put("Patterns", entity != null && entity.contains("Patterns", 9) ? entity.getList("Patterns", 10).copy() : new ListNBT());
        ContainerType<?> type = designerType();
        String label = DesignRules.clip(FlanBridge.label(claim), 64); // the client reads at most 64 characters
        NetworkHooks.openGui(player,
                new SimpleNamedContainerProvider((windowId, inventory, p) -> type.create(windowId, inventory, null), // 3-arg Forge method: exists on dedicated servers (the 2-arg one is client-only)
                        new StringTextComponent("Flag Design")),
                buf -> {
                    buf.writeUniqueId(session.claimId);
                    buf.writeLong(session.token);
                    buf.writeString(label);
                    buf.writeCompoundTag(data);
                    buf.writeVarInt(DesignRules.MAX_LAYERS);
                });
    }

    private static void sendResult(ServerPlayerEntity player, boolean ok, String message) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(1);
        buf.writeBoolean(ok);
        buf.writeString(DesignRules.clip(message, 256)); // the client reads at most 256 characters
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
            if (count < 0 || count > MAX_WIRE_LAYERS) {
                throw new IllegalArgumentException("layer count " + count);
            }
            List<String> hashes = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                hashes.add(buf.readString(DesignRules.MAX_ID_LENGTH)); // same limit the client writes with
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
        if (!mayUse(player, claim, session.station != null)) {
            sessions.remove(player.getUniqueID());
            sendResult(player, false, "You are no longer allowed to edit this flag.");
            return;
        }
        if (session.station != null) {
            ServerWorld stationWorld = FlanBridge.world(core.server(), stationDimension(session.station));
            BlockPos at = stationPos(session.station);
            ClaimRecord here = stationWorld == null || !isStation(stationWorld.getBlockState(at)) ? null
                    : stationClaim(player, stationWorld, at, false);
            if (here == null || !claimId.equals(here.claimId)) {
                sessions.remove(player.getUniqueID());
                sendResult(player, false, "This Flag Designer was removed, or its claim changed.");
                return;
            }
            if (player.world != stationWorld
                    || player.getDistanceSq(at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D) > STATION_REACH * STATION_REACH) {
                sendResult(player, false, "Move back to the Flag Designer to save.");
                return;
            }
        }
        if (!designerOpen(player)) {
            sendResult(player, false, "The designer is no longer open. Left-click the flag again.");
            return;
        }
        Set<String> known = new HashSet<>();
        for (BannerPattern p : BannerPattern.values()) {
            known.add(p.getHashname());
        }
        Set<String> unlocked = currentPatterns(BannerDesigns.fromSnbt(record.designSnbt));
        ItemStack slotItem = player.openContainer.getSlot(0).getStack(); // server-side inventory, not the client's word
        if (slotItem.getItem() instanceof BannerPatternItem) {
            unlocked.add(((BannerPatternItem) slotItem.getItem()).getBannerPattern().getHashname());
        }
        String problem = DesignRules.check(base, hashes, colors, known, itemLockedPatterns().keySet(), unlocked);
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

    /** Before the player data is saved on logout: closing returns the slot's pattern item. */
    public void onLogout(ServerPlayerEntity player) {
        if (designerOpen(player)) {
            player.closeContainer();
        }
        onLogout(player.getUniqueID());
    }

    public void onLogout(UUID player) {
        sessions.remove(player);
        lastOpen.remove(player);
    }
}
