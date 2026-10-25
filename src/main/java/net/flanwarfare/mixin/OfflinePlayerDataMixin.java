package net.flanwarfare.mixin;

import io.github.flemmli97.flan.player.OfflinePlayerData;
import net.flanwarfare.flan.Accounting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Same exemption as {@link PlayerClaimDataMixin} for Flan's offline-player accounting path,
 * which is used e.g. when resizing a claim whose owner is offline.
 */
@Mixin(value = OfflinePlayerData.class, remap = false)
public abstract class OfflinePlayerDataMixin {

    @Inject(method = "usedClaimBlocks", at = @At("RETURN"), cancellable = true, remap = false)
    private void flanwarfare$exemptConquered(CallbackInfoReturnable<Integer> cir) {
        OfflinePlayerData self = (OfflinePlayerData) (Object) this;
        int exempt = Accounting.conqueredBlocks(self.server, self.owner);
        if (exempt > 0) {
            cir.setReturnValue(Math.max(0, cir.getReturnValue() - exempt));
        }
    }
}
