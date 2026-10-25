package net.flanwarfare.protection;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ClaimState;
import net.flanwarfare.flan.FlanBridge;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.TNTEntity;
import net.minecraft.entity.item.minecart.TNTMinecartEntity;
import net.minecraft.entity.monster.CreeperEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.world.ExplosionEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-source explosion rules during raids.
 *
 * <p>Verified in Flan 1.7.2: Flan's {@code ExplosionEvent.Detonate} listener (normal priority)
 * removes each affected block for which {@code canInteract(null, EXPLOSIONS, pos)} is false. It
 * does not know what caused the explosion. This class records the source at HIGHEST priority,
 * before Flan runs, so the canInteract hook ({@code ClaimMixin} via {@code Guards}) can answer
 * per source; the context is cleared at LOWEST priority. Only individual blocks are kept or
 * removed; an explosion is never cancelled as a whole.
 */
public final class ExplosionRules {

    public enum Source { TNT, CREEPER, OTHER, MODDED }

    private static final ThreadLocal<Source> CURRENT = new ThreadLocal<>();

    /** Source of the explosion being processed, OTHER if called outside an explosion. */
    public static Source current() {
        Source source = CURRENT.get();
        return source == null ? Source.OTHER : source;
    }

    /**
     * True while Flan filters an explosion's block list. Flan also asks the same EXPLOSIONS
     * question (null player) from its LivingDamageEvent handler when an explosion hurts a
     * non-player, non-monster entity; that happens after Detonate, outside this context.
     */
    public static boolean inDetonation() {
        return CURRENT.get() != null;
    }

    static Source classify(Explosion explosion) {
        Entity exploder = explosion.getExploder();
        if (exploder == null) {
            return Source.OTHER;
        }
        if (exploder instanceof TNTEntity || exploder instanceof TNTMinecartEntity) {
            return Source.TNT;
        }
        if (exploder instanceof CreeperEntity) {
            return Source.CREEPER;
        }
        return exploder.getClass().getName().startsWith("net.minecraft.") ? Source.OTHER : Source.MODDED;
    }

    public static boolean allowed(Source source) {
        switch (source) {
            case TNT:
                return WarConfig.ALLOW_TNT_EXPLOSIONS.get();
            case CREEPER:
                return WarConfig.ALLOW_CREEPER_EXPLOSIONS.get();
            case MODDED:
                return WarConfig.ALLOW_MODDED_EXPLOSIONS.get();
            default:
                return WarConfig.ALLOW_OTHER_EXPLOSIONS.get();
        }
    }

    /** HIGHEST priority: remember the source before Flan filters the block list. */
    public void begin(ExplosionEvent.Detonate event) {
        CURRENT.set(classify(event.getExplosion()));
    }

    /**
     * LOWEST priority, after Flan and flag protection: optional entity protection, raid alerts
     * for the claims that actually lost blocks, then clear the context.
     */
    public void end(ExplosionEvent.Detonate event) {
        try {
            WarCore core = WarCore.ready();
            World world = event.getWorld();
            if (core == null || !(world instanceof ServerWorld) || !core.raid().isActive()) {
                return;
            }
            ServerWorld serverWorld = (ServerWorld) world;
            if (!WarConfig.ALLOW_EXPLOSION_ENTITY_DAMAGE.get()) {
                event.getAffectedEntities().removeIf(entity -> raidableClaim(core, serverWorld, entity.getPosition()) != null);
            }
            // Explosions set off by a player the owner authorized (e.g. their own TNT) are not raids.
            net.minecraft.entity.LivingEntity placer = event.getExplosion().getExplosivePlacedBy();
            UUID igniter = placer instanceof net.minecraft.entity.player.PlayerEntity ? placer.getUniqueID() : null;
            Map<UUID, Claim> damaged = new HashMap<>();
            for (BlockPos pos : event.getAffectedBlocks()) {
                Claim claim = raidableClaim(core, serverWorld, pos);
                if (claim != null && (igniter == null || !FlanBridge.isAuthorizedAt(claim, pos, igniter))) {
                    damaged.putIfAbsent(claim.getClaimID(), claim);
                }
            }
            for (Claim claim : damaged.values()) {
                ClaimRecord record = core.data().claim(claim.getClaimID());
                if (record != null && record.isWarEnabled()) {
                    core.alerts().hostileAction(claim, record, "an explosion");
                }
            }
        } finally {
            CURRENT.remove();
        }
    }

    /** The ordinary, flag-governed claim at a position whose protection raids open, else null. */
    private static Claim raidableClaim(WarCore core, ServerWorld world, BlockPos pos) {
        Claim claim = FlanBridge.parentAt(world, pos);
        if (claim == null || claim.isAdminClaim() || core.isExcluded(claim)) {
            return null;
        }
        ClaimRecord record = core.data().claim(claim.getClaimID());
        if (record == null || !(record.isWarEnabled() || record.state == ClaimState.PENDING_FLAG)) {
            return null;
        }
        return claim;
    }
}
