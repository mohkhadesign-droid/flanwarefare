package net.flanwarfare.mixin;

import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import net.flanwarfare.flan.Guards;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * Guards on Flan's {@link ClaimStorage}.
 *
 * <ul>
 *   <li>{@code resizeClaim}: blocks resizing claims without a flag, conquered claims (unless the
 *   config allows it), resizes during raid windows, and resizes that would leave the flag
 *   outside the claim.</li>
 *   <li>{@code transferOwner}: Flan's own transfer only checks that the caller owns the claim;
 *   it never consults PermissionCheckEvent. Blocks ordinary transfer of claims without a flag
 *   and of conquered land. The add-on's own conquest calls run with {@link Guards#bypass}.</li>
 *   <li>{@code createClaim}: the claim-slot limit counts {@code playerClaimMap.get(owner).size()}.
 *   The redirect counts only claims that are not conquered, so conquered land is slot-free.</li>
 * </ul>
 */
@Mixin(value = ClaimStorage.class, remap = false)
public abstract class ClaimStorageMixin {

    @Inject(method = "resizeClaim", at = @At("HEAD"), cancellable = true, remap = false)
    private void flanwarfare$guardResize(Claim claim, BlockPos from, BlockPos to, ServerPlayerEntity player,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (Guards.blockResize(claim, from, to, player)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "transferOwner", at = @At("HEAD"), cancellable = true, remap = false)
    private void flanwarfare$guardTransfer(Claim claim, ServerPlayerEntity player, UUID newOwner,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (Guards.blockTransfer(claim, player)) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(method = "createClaim",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;size()I", remap = false),
            remap = false)
    private int flanwarfare$countClaimSlots(Set<?> ownedClaims) {
        return Guards.countSlotClaims(ownedClaims);
    }
}
