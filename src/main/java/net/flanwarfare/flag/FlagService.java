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
import net.minecraft.item.DyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Flag placement, relocation, appearance, protection index and automatic restoration.
 *
 * <p>Geometry of a flag at position P (the block the owner stood in):
 * <ul>
 *   <li>FLAG: P, a standing vanilla banner.</li>
 *   <li>SUPPORT: the block under P. Cannot be broken, exploded or pushed.</li>
 *   <li>POCKET: the 3x3x2 space around P (excluding P). Must be free of collision when placed
 *   and nobody may place blocks into it afterwards, so the flag can always be reached by
 *   breaking ordinary blocks further out during a raid.</li>
 * </ul>
 */
public final class FlagService {

    public enum Role { NONE, FLAG, SUPPORT, POCKET }

    private final WarCore core;
    private final Map<String, Map<Long, Role>> roleIndex = new HashMap<>();
    private final Map<String, Map<Long, UUID>> flagIndex = new HashMap<>();

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
            BlockPos flag = record.flagPos();
            Map<Long, Role> roles = roleIndex.computeIfAbsent(record.dimension, d -> new HashMap<>());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        roles.putIfAbsent(flag.add(dx, dy, dz).toLong(), Role.POCKET);
                    }
                }
            }
            roles.put(flag.toLong(), Role.FLAG);
            roles.put(flag.down().toLong(), Role.SUPPORT);
            flagIndex.computeIfAbsent(record.dimension, d -> new HashMap<>()).put(flag.toLong(), record.claimId);
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

    /** /flag place: registers the flag of the owner's pending claim and activates it. */
    public int place(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getPosition();
        Claim claim = ownedParentHere(player);
        if (claim == null) {
            return 0;
        }
        ClaimRecord record = core.lifecycle().recordFor(claim);
        if (record.hasFlag) {
            Msg.send(player, Msg.error("This claim already has a flag. Use /flag move to relocate it."));
            return 0;
        }
        String problem = validate(world, pos, claim, record);
        if (problem != null) {
            Msg.send(player, Msg.error(problem));
            return 0;
        }
        String design = designFor(player.getUniqueID());
        int rotation = rotationOf(player);
        record.setFlagPos(pos);
        record.flagRotation = rotation;
        record.designSnbt = design;
        record.flagEverPlaced = true;
        record.flagPlacedAt = System.currentTimeMillis();
        record.state = ClaimState.ACTIVE;
        record.recoveryReason = null;
        record.restoreFailures = 0;
        rebuildIndex();
        core.data().markDirty();
        core.saveNow();
        BannerDesigns.place(world, pos, BannerDesigns.fromSnbt(design), rotation);
        core.audit("Flag placed for claim {} by {} at {} {}", record.claimId, player.getGameProfile().getName(),
                record.dimension, pos);
        Msg.send(player, Msg.ok("Flag placed. Your claim is now ACTIVE: protection and claim management are enabled."));
        return 1;
    }

    /** /flag move: instant, crash-safe relocation within the same claim, never during raids. */
    public int move(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getPosition();
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
        if (pos.equals(record.flagPos())) {
            Msg.send(player, Msg.error("The flag is already here."));
            return 0;
        }
        String problem = validate(world, pos, claim, record);
        if (problem != null) {
            Msg.send(player, Msg.error(problem));
            return 0;
        }
        relocate(world, record, pos, rotationOf(player));
        record.lastMovedAt = System.currentTimeMillis();
        core.data().markDirty();
        core.saveNow();
        core.audit("Flag of claim {} moved by owner to {}", record.claimId, pos);
        Msg.send(player, Msg.ok("Flag moved."));
        return 1;
    }

    /**
     * Crash-safe relocation: (1) the old position is queued as an orphan and the new position
     * recorded, then saved; (2) the new banner is placed; (3) the old banner is removed and the
     * orphan entry dropped. After a crash at any point the restore loop places the banner at the
     * recorded position and the orphan list removes the old one: never two flags, never none.
     */
    void relocate(ServerWorld world, ClaimRecord record, BlockPos to, int rotation) {
        BlockPos from = record.flagPos();
        OrphanFlag orphan = null;
        if (from != null) {
            orphan = new OrphanFlag(record.dimension, from.getX(), from.getY(), from.getZ());
            core.data().orphanFlags().add(orphan);
        }
        record.setFlagPos(to);
        record.flagRotation = rotation;
        rebuildIndex();
        core.data().markDirty();
        core.saveNow();
        BannerDesigns.place(world, to, BannerDesigns.fromSnbt(record.designSnbt), rotation);
        if (from != null && world.isBlockLoaded(from) && world.getBlockState(from).getBlock() instanceof BannerBlock) {
            world.setBlockState(from, Blocks.AIR.getDefaultState(), 3);
        }
        if (orphan != null && (from == null || world.isBlockLoaded(from))) {
            core.data().orphanFlags().remove(orphan);
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

    /** Returns null if the position is acceptable, otherwise a reason for the player. */
    public String validate(ServerWorld world, BlockPos pos, Claim claim, ClaimRecord self) {
        if (!claim.insideClaim(pos)) {
            return "The flag must be inside the claim.";
        }
        if (pos.getY() < 1 || pos.getY() > 253) {
            return "That height is not allowed for a flag.";
        }
        if (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()) {
            return "Stand in open air: no blocks, plants, snow layers or fluids where the flag would go.";
        }
        BlockState banner = BannerBlock.forColor(DyeColor.WHITE).getDefaultState();
        if (!banner.isValidPosition(world, pos)) {
            return "Stand on a solid block so the banner has support.";
        }
        BlockPos below = pos.down();
        BlockState support = world.getBlockState(below);
        if (support.getBlock() instanceof FallingBlock) {
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

    /** Shows the record's stored design on the banner now, or when its chunk next loads. */
    public void showDesign(ClaimRecord record) {
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        BlockPos pos = record.flagPos();
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            record.designPending = true;
            core.data().markDirty();
            return;
        }
        ItemStack design = BannerDesigns.fromSnbt(record.designSnbt);
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BannerBlock && state.getBlock() == BannerBlock.forColor(BannerDesigns.color(design))) {
            BannerDesigns.writePatterns(world, pos, design);
        } else {
            BannerDesigns.place(world, pos, design, record.flagRotation);
        }
        removeDroppedBanners(world, pos);
        record.designPending = false;
        core.data().markDirty();
    }

    /** Removes the flag of a claim that no longer exists (now, or once its chunk is loaded). */
    public void removeFlagOf(ClaimRecord record) {
        if (!record.hasFlag) {
            return;
        }
        BlockPos pos = record.flagPos();
        ServerWorld world = FlanBridge.world(core.server(), record.dimension);
        record.hasFlag = false;
        rebuildIndex();
        if (world != null && world.isBlockLoaded(pos)) {
            if (world.getBlockState(pos).getBlock() instanceof BannerBlock) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            }
        } else {
            core.data().orphanFlags().add(new OrphanFlag(record.dimension, pos.getX(), pos.getY(), pos.getZ()));
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
            BlockPos pos = record.flagPos();
            if (world == null || !world.isBlockLoaded(pos)) {
                continue;
            }
            if (!(world.getBlockState(pos).getBlock() instanceof BannerBlock)) {
                restore(world, record, false);
            } else if (record.designPending) {
                showDesign(record);
            }
        }
        removeLoadedOrphans();
    }

    /** Re-creates a missing banner. Returns null on success, otherwise the reason. */
    public String restore(ServerWorld world, ClaimRecord record, boolean byAdmin) {
        BlockPos pos = record.flagPos();
        String failure = null;
        BlockState here = world.getBlockState(pos);
        boolean replaceable = here.isAir() || here.getMaterial().isReplaceable() || !world.getFluidState(pos).isEmpty();
        if (here.getBlock() instanceof BannerBlock) {
            replaceable = true;
        }
        if (!replaceable) {
            failure = "flag position is occupied by " + here.getBlock().getRegistryName();
        } else {
            ItemStack design = BannerDesigns.fromSnbt(record.designSnbt);
            BlockState bannerState = BannerDesigns.standingState(design, record.flagRotation);
            if (!bannerState.isValidPosition(world, pos)) {
                BlockState below = world.getBlockState(pos.down());
                boolean belowReplaceable = below.isAir() || below.getMaterial().isReplaceable()
                        || !world.getFluidState(pos.down()).isEmpty();
                if (belowReplaceable) {
                    world.setBlockState(pos.down(), supportBlock().getDefaultState(), 3);
                } else {
                    failure = "support block under the flag is unsuitable: " + below.getBlock().getRegistryName();
                }
            }
            if (failure == null) {
                if (!world.getFluidState(pos).isEmpty() || !here.isAir()) {
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                }
                BannerDesigns.place(world, pos, design, record.flagRotation);
                removeDroppedBanners(world, pos);
            }
        }
        if (failure == null) {
            record.restoreFailures = 0;
            record.designPending = false;
            if (record.state == ClaimState.RECOVERY_REQUIRED && byAdmin) {
                record.state = ClaimState.ACTIVE;
                record.recoveryReason = null;
            }
            core.data().markDirty();
            core.audit("Flag of claim {} restored at {} {}", record.claimId, record.dimension, pos);
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

    private Block supportBlock() {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(WarConfig.RESTORE_SUPPORT_BLOCK.get()));
        return block == null || block == Blocks.AIR ? Blocks.COBBLESTONE : block;
    }

    /**
     * When something destroyed a flag and dropped a banner item, the restored flag would
     * otherwise duplicate it. Removes fresh banner item drops right next to the flag.
     */
    private static void removeDroppedBanners(ServerWorld world, BlockPos pos) {
        List<ItemEntity> drops = world.getEntitiesWithinAABB(ItemEntity.class, new AxisAlignedBB(pos).grow(2.0D),
                item -> item.getItem().getItem() instanceof BannerItem && item.ticksExisted < 400);
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
            BlockPos pos = new BlockPos(orphan.x, orphan.y, orphan.z);
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            if (claimOfFlagAt(orphan.dimension, pos) == null && world.getBlockState(pos).getBlock() instanceof BannerBlock) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            }
            it.remove();
            core.data().markDirty();
        }
    }

    // ------------------------------------------------------------------ admin

    /** Places a flag at the admin's position, activating a pending claim if necessary. */
    public String adminRelocate(ClaimRecord record, Claim claim, ServerPlayerEntity admin) {
        ServerWorld world = admin.getServerWorld();
        BlockPos pos = admin.getPosition();
        if (!FlanBridge.dimensionId(world).equals(record.dimension)) {
            return "You are not in the claim's dimension.";
        }
        String problem = validate(world, pos, claim, record);
        if (problem != null) {
            return problem;
        }
        if (record.designSnbt == null) {
            record.designSnbt = designFor(record.owner);
        }
        relocate(world, record, pos, rotationOf(admin));
        record.flagEverPlaced = true;
        if (record.flagPlacedAt == 0) {
            record.flagPlacedAt = System.currentTimeMillis();
        }
        record.state = ClaimState.ACTIVE;
        record.recoveryReason = null;
        record.restoreFailures = 0;
        core.data().markDirty();
        core.saveNow();
        core.audit("Admin {} relocated flag of claim {} to {}", admin.getGameProfile().getName(), record.claimId, pos);
        return null;
    }
}
