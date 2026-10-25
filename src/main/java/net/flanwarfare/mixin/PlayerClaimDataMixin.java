package net.flanwarfare.mixin;

import io.github.flemmli97.flan.player.PlayerClaimData;
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
 */
@Mixin(value = PlayerClaimData.class, remap = false)
public abstract class PlayerClaimDataMixin {

    @Shadow(remap = false)
    @Final
    private ServerPlayerEntity player;

    @Inject(method = "calculateUsedClaimBlocks", at = @At("RETURN"), cancellable = true, remap = false)
    private void flanwarfare$exemptConquered(CallbackInfoReturnable<Integer> cir) {
        int exempt = Accounting.conqueredBlocks(player.getServer(), player.getUniqueID());
        if (exempt > 0) {
            cir.setReturnValue(Math.max(0, cir.getReturnValue() - exempt));
        }
    }
}
