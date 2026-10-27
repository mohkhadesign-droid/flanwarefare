package net.flanwarfare.mixin;

import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.player.PlayerClaimData;
import net.flanwarfare.flag.ClaimTeleport;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.flanwarfare.flan.Accounting;
import net.minecraft.entity.player.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flan 1.7.2 computes used claim blocks live in the private {@code calculateUsedClaimBlocks()}
 * by summing {@code getPlane()} of every non-admin claim the player owns, in every world. There
 * is no stored counter to adjust, so the only way to make conquered land free (and stay free
 * across restarts and while offline) is to subtract it from that sum. Nothing is ever added, so
 * this cannot create claim blocks.
 *
 * <p>Claim teleports: Flan counts {@code trappedTick} down from 101 and teleports to
 * {@code tpPos} on the tick it reaches 0. Just before that tick, {@link ClaimTeleport} may take
 * over and put the player beside the claim's flag (or cancel when nothing is safe). Flan's
 * permission check, delay and movement cancel run unchanged.
 */
@Mixin(value = PlayerClaimData.class, remap = false)
public abstract class PlayerClaimDataMixin {

    @Shadow(remap = false)
    @Final
    private ServerPlayerEntity player;

    @Shadow(remap = false)
    private BlockPos tpPos;

    @Shadow(remap = false)
    private int trappedTick;

    @Inject(method = "tick(Lio/github/flemmli97/flan/claim/Claim;Ljava/util/function/Consumer;)V", at = @At("HEAD"), remap = false)
    private void flanwarfare$teleportBesideFlag(Claim currentClaim, java.util.function.Consumer<Claim> cons, CallbackInfo ci) {
        // Flan decrements trappedTick first and teleports when it hits 0: this is that tick.
        if (tpPos != null && trappedTick == 1 && ClaimTeleport.teleportNow(player, tpPos)) {
            tpPos = null;
            trappedTick = 0; // Flan's decrement makes it -1: nothing more happens
        }
    }

    @Inject(method = "calculateUsedClaimBlocks", at = @At("RETURN"), cancellable = true, remap = false)
    private void flanwarfare$exemptConquered(CallbackInfoReturnable<Integer> cir) {
        int exempt = Accounting.conqueredBlocks(player.getServer(), player.getUniqueID());
        if (exempt > 0) {
            cir.setReturnValue(Math.max(0, cir.getReturnValue() - exempt));
        }
    }
}
