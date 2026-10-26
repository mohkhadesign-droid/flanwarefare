package net.flanwarfare.flag;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.data.OrphanFlag;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.util.Msg;
import net.minecraft.block.BannerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.BannerItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.DyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Flag placement, relocation, appearance, capture animation, protection index and automatic
 * restoration. Geometry is described by {@link FlagStructure}; the base is always the record's
 * flag position and the capture-zone centre.
 *
 * <p>Protection roles:
 * <ul>
 *   <li>FLAG: every pole block and every position the banner can hang at. Cannot be broken,
 *   replaced, exploded, pushed, or built into.</li>
 *   <li>SUPPORT: the block under the base. Cannot be broken, exploded or pushed.</li>
 *   <li>POCKET: the 3x3x2 space around the base. Must be clear when placed and nobody may place
 *   blocks into it afterwards, so the flag can always be reached by breaking ordinary blocks
 *   further out during a raid.</li>
 * </ul>
 */
public final class FlagService {

    public enum Role { NONE, FLAG, SUPPORT, POCKET }

    /** What the banner currently shows while a capture animates it. */
    private static final class Visual {
        final int level;
        final String design;

        Visual(int level, String design) {
            this.level = level;
            this.design = design;
        }
    }

    private final WarCore core;
    private final Map<String, Map<Long, Role>> roleIndex = new HashMap<>();
    private final Map<String, Map<Long, UUID>> flagIndex = new HashMap<>();
    private final Map<UUID, Visual> visuals = new HashMap<>();
    private final java.util.Set<UUID> conversionChecked = new java.util.HashSet<>();

    public FlagService(WarCore core) {
        this.core = core;
    }

    // ------------------------------------------------------------------ index

    public void rebuildIndex() {
        roleIndex.clear();
        flagIndex.clear();
        if (core.data() == null) {
            return;
        }
        for (ClaimRecord record : core.data().claims().values()) {
            if (!record.hasFlag) {
                continue;
            }
            FlagStructure s = FlagStructure.of(record);
            Map<Long, Role> roles = roleIndex.computeIfAbsent(record.dimension, d -> new HashMap<>());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        roles.putIfAbsent(s.base.add(dx, dy, dz).toLong(), Role.POCKET);
                    }
                }
            }
            for (BlockPos pos : s.allBlocks()) {
                roles.put(pos.toLong(), Role.FLAG);
            }
            roles.put(s.support().toLong(), Role.SUPPORT);
            flagIndex.computeIfAbsent(record.dimension, d -> new HashMap<>()).put(s.base.toLong(), record.claimId);
        }
    }

    /** Called once at startup: every flag's appearance is re-applied when its chunk is loaded. */
    public void refreshAllOnLoad() {
        for (ClaimRecord record : core.data().claims().values()) {
            if (record.hasFlag) {
                record.designPending = true;
            }
        }
    }

    public Role roleAt(World world, BlockPos pos) {
        Map<Long, Role> roles = roleIndex.get(FlanBridge.dimensionId(world));
        if (roles == null) {
            return Role.NONE;
        }
        Role role = roles.get(pos.toLong());
        return role == null ? Role.NONE : role;
    }

    public boolean isProtectedFlagBlock(World world, BlockPos pos) {
        if (!WarConfig.PROTECT_FLAGS.get()) {
            return false;
        }
        Role role = roleAt(world, pos);
        return role == Role.FLAG || role == Role.SUPPORT;
    }

    public UUID claimOfFlagAt(String dimension, BlockPos pos) {
        Map<Long, UUID> flags = flagIndex.get(dimension);
        return flags == null ? null : flags.get(pos.toLong());
    }

    // ------------------------------------------------------------------ commands

    /** The structure a new or moved flag at the player's feet would get, per configuration. */
    private static FlagStructure plannedFor(ServerPlayerEntity player) {
        BlockPos base = player.getPosition();
        if (useModel()) {
            return FlagStructure.planned(base, FlagStructure.MODEL_HEIGHT, player.getHorizontalFacing(), FlagStructure.MODEL_BLOCK);
        }
        if (!WarConfig.POLE_ENABLED.get()) {
            return FlagStructure.planned(base, 0, Direction.NORTH, null);
        }
        Block pole = FlagStructure.resolvePoleBlock(WarConfig.POLE_BLOCK.get());
        return FlagStructure.planned(base, WarConfig.POLE_HEIGHT.get(), player.getHorizontalFacing(),
                pole.getRegistryName().toString());
    }

    /** True if new flags use the Flan Warfare Flags model (style AUTO with the mod, or MODEL). */
    public static boolean useModel() {
        String style = WarConfig.POLE_STYLE.get();
        return !"BLOCKS".equalsIgnoreCase(style) && FlagStructure.modelAvailable();
    }

    private static void applyStructure(ClaimRecord record, FlagStructure s, int rotation) {
        record.setFlagPos(s.base);
        record.flagRotation = rotation;
        record.poleHeight = s.height;
        record.poleSide = s.side.getIndex();
        record.poleBlock = s.poleBlockId;
    }

    /** /flag place: registers the flag of the owner's pending claim and activates it. */
    public int place(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Claim claim = ownedParentHere(player);
        if (claim == null) {
            return 0;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        if (record.hasFlag) {
            Msg.send(player, Msg.error("This claim already has a flag. Use /flag move to relocate it."));
            return 0;
        }
        FlagStructure planned = plannedFor(player);
        String problem = validate(world, planned, claim, record);
        if (problem != null) {
            Msg.send(player, Msg.error(problem));
            return 0;
        }
        applyStructure(record, planned, rotationOf(player));
        record.designSnbt = designFor(player.getUniqueID());
        record.flagEverPlaced = true;
        record.flagPlacedAt = System.currentTimeMillis();
        record.state = ClaimState.ACTIVE;
        record.recoveryReason = null;
        record.restoreFailures = 0;
        rebuildIndex();
        core.data().markDirty();
        core.saveNow();
        build(world, record);
        moveOffPole(player, planned);
        core.audit("Flag placed for claim {} by {} at {} {}", record.claimId, player.getGameProfile().getName(),
                record.dimension, planned.base);
        Msg.send(player, Msg.ok("Flag placed. Your claim is now ACTIVE: protection and claim management are enabled."));
        return 1;
    }

    /** /flag move: instant, crash-safe relocation within the same claim, never during raids. */
    public int move(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Claim claim = ownedParentHere(player);
        if (claim == null) {
            return 0;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        if (!record.hasFlag) {
            Msg.send(player, Msg.error("This claim has no flag yet. Use /flag place."));
            return 0;
        }
        if (record.state != ClaimState.ACTIVE) {
            Msg.send(player, Msg.error("This claim's flag needs administrator recovery before it can be moved."));
            return 0;
        }
        if (core.raid().isActive()) {
            Msg.send(player, Msg.error("Flags cannot be moved while a raid is active."));
            return 0;
        }
        long cooldown = WarConfig.MOVEMENT_COOLDOWN_SECONDS.get() * 1000L;
        long since = System.currentTimeMillis() - record.lastMovedAt;
        if (record.lastMovedAt > 0 && since < cooldown) {
            Msg.send(player, Msg.error("You can move this flag again in " + Msg.duration(cooldown - since) + "."));
            return 0;
        }
        FlagStructure planned = plannedFor(player);
        if (planned.base.equals(record.flagPos())) {
            Msg.send(player, Msg.error("The flag is already here."));
            return 0;
        }
        String problem = validate(world, planned, claim, record);
        if (problem != null) {
            Msg.send(player, Msg.error(problem));
            return 0;
        }
        relocate(world, record, planned, rotationOf(player));
        record.lastMovedAt = System.currentTimeMillis();
        core.data().markDirty();
        core.saveNow();
        moveOffPole(player, planned);
        core.audit("Flag of claim {} moved by owner to {}", record.claimId, planned.base);
        Msg.send(player, Msg.ok("Flag moved."));
        return 1;
    }

    /**
     * Crash-safe relocation: (1) the old structure is queued as an orphan and the new one
     * recorded, then saved; (2) the new structure is built; (3) the old one is removed and the
     * orphan entry dropped. After a crash at any point the restore loop builds the recorded
     * structure and the orphan list removes the old one: never two flags, never none.
     */
    void relocate(ServerWorld world, ClaimRecord record, FlagStructure to, int rotation) {
        FlagStructure from = record.hasFlag ? FlagStructure.of(record) : null;
        OrphanFlag orphan = null;
        if (from != null) {
            orphan = orphanOf(record.dimension, from);
            core.data().orphanFlags().add(orphan);
        }
        visuals.remove(record.claimId);
        applyStructure(record, to, rotation);
        rebuildIndex();
        core.data().markDirty();
        core.saveNow();
        build(world, record);
        if (from != null && world.isBlockLoaded(from.base)) {
            removeStructure(world, from);
            core.data().orphanFlags().remove(orphan);
        }
    }

    private static OrphanFlag orphanOf(String dimension, FlagStructure s) {
        OrphanFlag orphan = new OrphanFlag(dimension, s.base.getX(), s.base.getY(), s.base.getZ());
        orphan.poleHeight = s.height;
        orphan.poleSide = s.side.getIndex();
        orphan.poleBlock = s.poleBlockId;
        return orphan;
    }

    /** Players standing in the new pole are moved to the free spot opposite the banner. */
    private static void moveOffPole(ServerPlayerEntity player, FlagStructure s) {
        if (!s.isPole()) {
            return;
        }
        AxisAlignedBB column = new AxisAlignedBB(s.base).expand(0, s.height, 0);
        if (player.getBoundingBox().intersects(column)) {
            BlockPos spot = s.standingSpot();
            player.connection.setPlayerLocation(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                    player.rotationYaw, player.rotationPitch);
        }
    }

    public int saveDesign(ServerPlayerEntity player) {
        ItemStack held = player.getHeldItemMainhand();
        if (!BannerDesigns.isBanner(held)) {
            Msg.send(player, Msg.error("Hold a banner in your main hand. It will not be consumed."));
            return 0;
        }
        core.data().setDesign(player.getUniqueID(), BannerDesigns.toSnbt(held));
        Msg.send(player, Msg.ok("Banner design saved. Use /flag design apply inside your claim to show it."));
        return 1;
    }

    public int resetDesign(ServerPlayerEntity player) {
        core.data().setDesign(player.getUniqueID(), null);
        Msg.send(player, Msg.ok("Your saved design was reset to the default banner. Use /flag design apply to show it."));
        return 1;
    }

    public int applyDesign(ServerPlayerEntity player) {
        Claim claim = ownedParentHere(player);
        if (claim == null) {
            return 0;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        if (!record.hasFlag) {
            Msg.send(player, Msg.error("This claim has no flag yet. Use /flag place."));
            return 0;
        }
        record.designSnbt = designFor(player.getUniqueID());
        core.data().markDirty();
        if (visuals.containsKey(record.claimId)) {
            Msg.send(player, Msg.ok("Design saved for this flag. It will show once the current capture attempt ends."));
            return 1;
        }
        showDesign(record);
        Msg.send(player, Msg.ok("Flag design updated."));
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    /** The parent claim at the player's feet if they own it; sends the reason otherwise. */
    private Claim ownedParentHere(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Claim claim = FlanBridge.parentAt(world, player.getPosition());
        if (claim == null) {
            Msg.send(player, Msg.error("Stand inside one of your claims."));
            return null;
        }
        if (claim.isAdminClaim()) {
            Msg.send(player, Msg.error("Admin claims never have conquest flags."));
            return null;
        }
        if (core.isExcluded(claim)) {
            Msg.send(player, Msg.error("This claim is excluded from warfare and needs no flag."));
            return null;
        }
        if (!claim.getOwner().equals(player.getUniqueID())) {
            Msg.send(player, Msg.error("Only the claim owner can do this."));
            return null;
        }
        return claim;
    }

    public String designFor(UUID player) {
        String design = core.data().design(player);
        return design != null ? design : BannerDesigns.defaultSnbt();
    }

    private static int rotationOf(ServerPlayerEntity player) {
        // Same formula vanilla uses when a player places a standing banner.
        return MathHelper.floor((180.0F + player.rotationYaw) * 16.0F / 360.0F + 0.5D) & 15;
    }

    private static boolean isAir(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir() && world.getFluidState(pos).isEmpty();
    }

    /** Returns null if the structure can be built here, otherwise a reason for the player. */
    public String validate(ServerWorld world, FlagStructure s, Claim claim, ClaimRecord self) {
        BlockPos pos = s.base;
        if (!claim.insideClaim(pos)) {
            return "The flag must be inside the claim.";
        }
        if (pos.getY() < 1 || pos.getY() + Math.max(1, s.height) > 254) {
            return "That height is not allowed for a flag.";
        }
        if (!isAir(world, pos) || !isAir(world, pos.up())) {
            return "Stand in open air: no blocks, plants, snow layers or fluids where the flag would go.";
        }
        for (BlockPos p : s.poleBlocks()) {
            if (!isAir(world, p)) {
                return "Not enough open space above you: the flagpole needs " + (s.height + 1)
                        + " free blocks straight up. No blocks are replaced to make room.";
            }
        }
        if (s.isPole()) {
            for (BlockPos p : s.bannerSlots()) {
                if (!isAir(world, p) || !claim.insideClaim(p)) {
                    return "The banner needs open space on the side you are facing, " + s.height
                            + " blocks up from the ground, inside the claim. Turn to face open space.";
                }
            }
        }
        BlockState standing = BannerBlock.forColor(DyeColor.WHITE).getDefaultState();
        if (!standing.isValidPosition(world, pos)) {
            return "Stand on a solid block so the flag has support.";
        }
        BlockPos below = s.support();
        if (world.getBlockState(below).getBlock() instanceof FallingBlock) {
            return "The flag cannot stand on sand, gravel or other falling blocks.";
        }
        if (world.getTileEntity(below) != null) {
            return "The flag cannot stand on a container, machine or other block entity.";
        }
        if (!claim.insideClaim(below)) {
            return "The block under the flag must be inside the claim.";
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = pos.add(dx, dy, dz);
                    if (world.getFluidState(p).isTagged(FluidTags.LAVA) || world.getBlockState(p).getBlock() == Blocks.FIRE) {
                        return "Too close to lava or fire.";
                    }
                    if (dy < 0 || p.equals(pos)) {
                        continue;
                    }
                    if (!claim.insideClaim(p)) {
                        return "Too close to the claim border: the flag needs a clear 3x3 space inside the claim.";
                    }
                    if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) {
                        return "The flag needs a clear 3x3 space, two blocks high, around it.";
                    }
                }
            }
        }
        java.util.Set<Long> own = self.hasFlag ? footprint(FlagStructure.of(self)) : Collections.<Long>emptySet();
        for (Long p : footprint(s)) {
            if (roleAt(world, BlockPos.fromLong(p)) != Role.NONE && !own.contains(p)) {
                return "Too close to another flag.";
            }
        }
        int min = WarConfig.MIN_FLAG_DISTANCE.get();
        String dim = FlanBridge.dimensionId(world);
        for (ClaimRecord other : core.data().claims().values()) {
            if (other == self || !other.hasFlag || !other.dimension.equals(dim)) {
                continue;
            }
            if (other.flagPos().distanceSq(pos) < (double) min * min) {
                return "Too close to another flag (minimum " + min + " blocks).";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ building and appearance

    /** Builds the whole structure (pole, support if missing, banner at the current level). */
    private void build(ServerWorld world, ClaimRecord record) {
        FlagStructure s = FlagStructure.of(record);
        for (BlockPos p : s.poleBlocks()) {
            world.setBlockState(p, s.poleStateAt(p), 3);
        }
        applyCurrent(world, record);
    }

    /** Expected banner level and design right now (animation override, else raised owner flag). */
    private Visual expected(ClaimRecord record) {
        Visual visual = visuals.get(record.claimId);
        if (visual != null) {
            return visual;
        }
        return new Visual(FlagStructure.of(record).topLevel(), record.designSnbt);
    }

    /** Puts the banner where it should be, with the right design; clears other banner slots. */
    private void applyCurrent(ServerWorld world, ClaimRecord record) {
        FlagStructure s = FlagStructure.of(record);
        Visual visual = expected(record);
        ItemStack design = BannerDesigns.fromSnbt(visual.design);
        if (s.isModel()) {
            writeModel(world, s, design, s.raiseAt(visual.level));
            record.designPending = false;
            core.data().markDirty();
            return;
        }
        BlockPos target = s.bannerAt(visual.level);
        for (BlockPos slot : s.bannerSlots()) {
            if (!slot.equals(target) && FlagStructure.isBanner(world.getBlockState(slot))) {
                world.setBlockState(slot, Blocks.AIR.getDefaultState(), 3);
            }
        }
        BlockState current = world.getBlockState(target);
        if (s.isPole()) {
            BlockState wanted = BannerDesigns.wallState(design, s.side);
            if (current.getBlock() == wanted.getBlock()) {
                BannerDesigns.writePatterns(world, target, design);
            } else {
                BannerDesigns.placeWall(world, target, design, s.side);
            }
        } else if (current.getBlock() instanceof BannerBlock && current.getBlock() == BannerBlock.forColor(BannerDesigns.color(design))) {
            BannerDesigns.writePatterns(world, target, design);
        } else {
            BannerDesigns.place(world, target, design, record.flagRotation);
        }
        removeDrops(world, s);
        record.designPending = false;
        core.data().markDirty();
    }

    /**
     * Model flags: the design (base colour + vanilla pattern list) and the raise height go into
     * the base tile entity of the Flan Warfare Flags block, through plain NBT, so this mod has
     * no compile-time dependency on it. Clients get the change through the tile entity's update
     * packet. Unchanged data sends nothing.
     */
    private static void writeModel(ServerWorld world, FlagStructure s, ItemStack design, float raise) {
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(s.base);
        if (te == null) {
            return; // restore() rebuilds the base block
        }
        net.minecraft.nbt.CompoundNBT data = te.write(new net.minecraft.nbt.CompoundNBT());
        net.minecraft.nbt.CompoundNBT before = data.copy();
        data.putInt("Base", BannerDesigns.color(design).getId());
        net.minecraft.nbt.CompoundNBT source = design.getChildTag("BlockEntityTag");
        if (source != null && source.contains("Patterns", 9)) {
            data.put("Patterns", source.getList("Patterns", 10).copy());
        } else {
            data.put("Patterns", new net.minecraft.nbt.ListNBT());
        }
        data.putFloat("Raise", raise);
        if (data.equals(before)) {
            return;
        }
        BlockState state = world.getBlockState(s.base);
        te.read(state, data);
        te.markDirty();
        world.notifyBlockUpdate(s.base, state, state, 3);
    }

    /** Shows the record's stored design, fully raised (ends any animation override). */
    public void showDesign(ClaimRecord record) {
        visuals.remove(record.claimId);
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        if (world == null || !record.hasFlag || !world.isBlockLoaded(record.flagPos())) {
            record.designPending = true;
            core.data().markDirty();
            return;
        }
        applyCurrent(world, record);
    }

    /**
     * Capture animation: shows {@code design} at {@code level}. Only changes blocks when the
     * frame actually changes, so a frozen (contested) capture sends no block updates.
     */
    public void setVisual(ClaimRecord record, int level, String design) {
        if (!record.hasFlag || record.poleHeight <= 0) {
            return;
        }
        Visual old = visuals.get(record.claimId);
        if (old != null && old.level == level && old.design.equals(design)) {
            return;
        }
        visuals.put(record.claimId, new Visual(level, design));
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        if (world != null && world.isBlockLoaded(record.flagPos())) {
            applyCurrent(world, record);
        }
    }

    /** Ends the animation: the current owner's banner returns to the top. */
    public void clearVisual(ClaimRecord record) {
        if (visuals.containsKey(record.claimId)) {
            showDesign(record);
        }
    }

    public boolean isAnimating(UUID claimId) {
        return visuals.containsKey(claimId);
    }

    /** Removes a structure's own blocks (pole blocks of its type, banners) without drops. */
    private void removeStructure(ServerWorld world, FlagStructure s) {
        for (BlockPos p : s.bannerSlots()) {
            if (FlagStructure.isBanner(world.getBlockState(p)) && !belongsToLiveFlag(world, p)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            }
        }
        for (BlockPos p : s.poleBlocks()) {
            if (s.isPoleBlock(world.getBlockState(p)) && !belongsToLiveFlag(world, p)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            }
        }
    }

    private boolean belongsToLiveFlag(World world, BlockPos pos) {
        return roleAt(world, pos) == Role.FLAG;
    }

    /** Removes the flag of a claim that no longer exists (now, or once its chunk is loaded). */
    public void removeFlagOf(ClaimRecord record) {
        if (!record.hasFlag) {
            return;
        }
        FlagStructure s = FlagStructure.of(record);
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        record.hasFlag = false;
        visuals.remove(record.claimId);
        rebuildIndex();
        if (world != null && world.isBlockLoaded(s.base)) {
            removeStructure(world, s);
        } else {
            core.data().orphanFlags().add(orphanOf(record.dimension, s));
        }
        core.data().markDirty();
    }

    // ------------------------------------------------------------------ restoration

    public void tick(long ticks) {
        long interval = WarConfig.RESTORE_CHECK_SECONDS.get() * 20L;
        if (ticks % interval != 0) {
            return;
        }
        for (ClaimRecord record : new ArrayList<>(core.data().claims().values())) {
            if (!record.hasFlag) {
                continue;
            }
            ServerWorld world = FlanBridge.world(core.server(), record.dimension);
            if (world == null || !world.isBlockLoaded(record.flagPos())) {
                continue;
            }
            convertIfNeeded(world, record);
            if (!intact(world, record)) {
                restore(world, record, false);
            } else if (record.designPending) {
                applyCurrent(world, record);
            }
        }
        removeLoadedOrphans();
    }

    /**
     * Converts an existing flag between the vanilla-block form and the Flan Warfare Flags model
     * when the configured style differs (for example right after the mod was installed, or if
     * it was removed). Done once per flag per server run, never during a capture animation,
     * and only if the new column is free (air, plants, or blocks of the old flag): nothing else
     * is ever replaced. The old structure is removed through the normal relocation path.
     */
    private void convertIfNeeded(ServerWorld world, ClaimRecord record) {
        FlagStructure s = FlagStructure.of(record);
        boolean wantModel = useModel();
        if (s.isModel() == wantModel || (!wantModel && !s.isModel())) {
            return;
        }
        if (visuals.containsKey(record.claimId) || !conversionChecked.add(record.claimId)) {
            return;
        }
        FlagStructure to;
        if (wantModel) {
            to = FlagStructure.planned(s.base, FlagStructure.MODEL_HEIGHT, s.isPole() ? s.side : Direction.NORTH,
                    FlagStructure.MODEL_BLOCK);
        } else if (WarConfig.POLE_ENABLED.get()) {
            Block pole = FlagStructure.resolvePoleBlock(WarConfig.POLE_BLOCK.get());
            to = FlagStructure.planned(s.base, WarConfig.POLE_HEIGHT.get(), s.side, pole.getRegistryName().toString());
        } else {
            to = FlagStructure.planned(s.base, 0, Direction.NORTH, null);
        }
        for (BlockPos p : to.allBlocks()) {
            BlockState here = world.getBlockState(p);
            boolean free = replaceable(world, p, here) || s.isPoleBlock(here) || FlagStructure.isBanner(here);
            if (!free) {
                core.audit("Flag of claim {} kept its old form: {} is occupied by {} (convert later with /flag move or "
                        + "/war admin flag relocate)", record.claimId, p, here.getBlock().getRegistryName());
                return;
            }
        }
        relocate(world, record, to, record.flagRotation);
        core.audit("Flag of claim {} converted to the {} form at {}", record.claimId,
                wantModel ? "banner-and-pole model" : "vanilla block", s.base);
    }

    /** True if every pole block is in place and the banner is at its expected slot. */
    public boolean intact(ServerWorld world, ClaimRecord record) {
        FlagStructure s = FlagStructure.of(record);
        for (BlockPos p : s.poleBlocks()) {
            if (!s.isPoleBlock(world.getBlockState(p))) {
                return false;
            }
        }
        if (s.isModel()) {
            return world.getTileEntity(s.base) != null;
        }
        BlockPos target = s.bannerAt(expected(record).level);
        for (BlockPos slot : s.bannerSlots()) {
            boolean banner = FlagStructure.isBanner(world.getBlockState(slot));
            if (slot.equals(target) != banner) {
                return false;
            }
        }
        return true;
    }

    /** Re-creates missing parts of a flag. Returns null on success, otherwise the reason. */
    public String restore(ServerWorld world, ClaimRecord record, boolean byAdmin) {
        FlagStructure s = FlagStructure.of(record);
        String failure = null;
        for (BlockPos p : s.poleBlocks()) {
            BlockState here = world.getBlockState(p);
            if (s.isPoleBlock(here) && (!s.isModel() || here.equals(s.poleStateAt(p)))) {
                continue;
            }
            if (replaceable(world, p, here) || s.isPoleBlock(here)) {
                world.setBlockState(p, s.poleStateAt(p), 3);
            } else {
                failure = "flagpole position " + p + " is occupied by " + here.getBlock().getRegistryName();
                break;
            }
        }
        if (failure == null && s.isModel()) {
            if (replaceable(world, s.support(), world.getBlockState(s.support()))) {
                world.setBlockState(s.support(), supportBlock().getDefaultState(), 3);
            }
            applyCurrent(world, record);
        } else if (failure == null) {
            Visual visual = expected(record);
            BlockPos target = s.bannerAt(visual.level);
            BlockState here = world.getBlockState(target);
            if (!FlagStructure.isBanner(here) && !replaceable(world, target, here)) {
                failure = "banner position is occupied by " + here.getBlock().getRegistryName();
            } else if (!s.isPole()) {
                BlockState standing = BannerDesigns.standingState(BannerDesigns.fromSnbt(visual.design), record.flagRotation);
                if (!standing.isValidPosition(world, target)) {
                    BlockState below = world.getBlockState(s.support());
                    if (replaceable(world, s.support(), below)) {
                        world.setBlockState(s.support(), supportBlock().getDefaultState(), 3);
                    } else {
                        failure = "support block under the flag is unsuitable: " + below.getBlock().getRegistryName();
                    }
                }
            } else if (replaceable(world, s.support(), world.getBlockState(s.support()))) {
                world.setBlockState(s.support(), supportBlock().getDefaultState(), 3);
            }
            if (failure == null) {
                if (!FlagStructure.isBanner(here) && !here.isAir()) {
                    world.setBlockState(target, Blocks.AIR.getDefaultState(), 3);
                }
                applyCurrent(world, record);
            }
        }
        if (failure == null) {
            record.restoreFailures = 0;
            if (record.state == ClaimState.RECOVERY_REQUIRED && byAdmin) {
                record.state = ClaimState.ACTIVE;
                record.recoveryReason = null;
            }
            core.data().markDirty();
            core.audit("Flag of claim {} restored at {} {}", record.claimId, record.dimension, s.base);
            return null;
        }
        record.restoreFailures++;
        if (record.restoreFailures >= 3 && record.state == ClaimState.ACTIVE) {
            record.state = ClaimState.RECOVERY_REQUIRED;
            record.recoveryReason = "Automatic flag restoration failed: " + failure;
            Msg.admins(core.server(), Msg.error("Claim " + record.claimId + " needs flag recovery: " + failure
                    + ". It stays raidable and capturable. See /war admin claim inspect " + record.claimId));
            core.audit("Claim {} entered RECOVERY_REQUIRED: {}", record.claimId, failure);
        }
        core.data().markDirty();
        return failure;
    }

    private static boolean replaceable(ServerWorld world, BlockPos pos, BlockState state) {
        return state.isAir() || state.getMaterial().isReplaceable() || !world.getFluidState(pos).isEmpty();
    }

    private Block supportBlock() {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(WarConfig.RESTORE_SUPPORT_BLOCK.get()));
        return block == null || block == Blocks.AIR ? Blocks.COBBLESTONE : block;
    }

    /**
     * When something destroyed part of a flag and dropped its item, the restored flag would
     * otherwise duplicate it. Removes fresh banner and pole-block drops around the structure.
     */
    private static void removeDrops(ServerWorld world, FlagStructure s) {
        Block pole = s.isPole() ? s.poleBlock() : null;
        AxisAlignedBB area = new AxisAlignedBB(s.base).grow(2.0D).expand(0, Math.max(0, s.height), 0);
        List<ItemEntity> drops = world.getEntitiesWithinAABB(ItemEntity.class, area, item -> {
            if (item.ticksExisted >= 400) {
                return false;
            }
            ItemStack stack = item.getItem();
            if (stack.getItem() instanceof BannerItem) {
                return true;
            }
            return pole != null && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() == pole;
        });
        for (ItemEntity drop : drops) {
            drop.remove();
        }
    }

    private void removeLoadedOrphans() {
        Iterator<OrphanFlag> it = core.data().orphanFlags().iterator();
        while (it.hasNext()) {
            OrphanFlag orphan = it.next();
            ServerWorld world = FlanBridge.world(core.server(), orphan.dimension);
            if (world == null) {
                it.remove();
                core.data().markDirty();
                continue;
            }
            FlagStructure s = FlagStructure.of(orphan);
            if (!world.isBlockLoaded(s.base)) {
                continue;
            }
            removeStructure(world, s);
            it.remove();
            core.data().markDirty();
        }
    }

    // ------------------------------------------------------------------ admin

    /** Builds a flag at the admin's position, activating a pending claim if necessary. */
    public String adminRelocate(ClaimRecord record, Claim claim, ServerPlayerEntity admin) {
        ServerWorld world = admin.getServerWorld();
        if (!FlanBridge.dimensionId(world).equals(record.dimension)) {
            return "You are not in the claim's dimension.";
        }
        FlagStructure planned = plannedFor(admin);
        String problem = validate(world, planned, claim, record);
        if (problem != null) {
            return problem;
        }
        if (record.designSnbt == null) {
            record.designSnbt = designFor(record.owner);
        }
        relocate(world, record, planned, rotationOf(admin));
        moveOffPole(admin, planned);
        record.flagEverPlaced = true;
        if (record.flagPlacedAt == 0) {
            record.flagPlacedAt = System.currentTimeMillis();
        }
        record.state = ClaimState.ACTIVE;
        record.recoveryReason = null;
        record.restoreFailures = 0;
        core.data().markDirty();
        core.saveNow();
        core.audit("Admin {} relocated flag of claim {} to {}", admin.getGameProfile().getName(), record.claimId, planned.base);
        return null;
    }

    /** Short description of a flag's structure for /flag info and admin inspect. */
    public static String describe(ClaimRecord record) {
        if (!record.hasFlag) {
            return "no flag";
        }
        String base = record.flagX + ", " + record.flagY + ", " + record.flagZ;
        return record.poleHeight > 0
                ? "flagpole at " + base + " (banner up to " + record.poleHeight + " blocks high)"
                : "banner at " + base;
    }

    /** Every position a structure protects: its blocks, support and the pocket. */
    private static java.util.Set<Long> footprint(FlagStructure s) {
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    set.add(s.base.add(dx, dy, dz).toLong());
                }
            }
        }
        for (BlockPos pos : s.allBlocks()) {
            set.add(pos.toLong());
        }
        set.add(s.support().toLong());
        return set;
    }
}
