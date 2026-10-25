package net.flanwarfare.mixin;

import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.flan.Guards;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Operation-level guards on Flan's {@link Claim}.
 *
 * <ul>
 *   <li>{@code canInteract} with a null player is how Flan evaluates explosions, fire spread,
 *   fluids and pistons (verified in WorldEvents). Those paths never post PermissionCheckEvent,
 *   so this is the only place raid configuration can open them. Off by default.</li>
 *   <li>{@code setPlayerGroup}, {@code setHomePos}, {@code setClaimName} are reachable from GUI
 *   sub-screens that do not re-check permissions; blocking them here keeps claims without a
 *   flag unmanageable regardless of which command or GUI path is used.</li>
 * </ul>
 */
@Mixin(value = Claim.class, remap = false)
public abstract class ClaimMixin {

    @Inject(method = "canInteract(Lnet/minecraft/entity/player/ServerPlayerEntity;Lio/github/flemmli97/flan/api/permission/ClaimPermission;Lnet/minecraft/util/math/BlockPos;Z)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void flanwarfare$worldEffects(ServerPlayerEntity player, ClaimPermission permission, BlockPos pos,
                                          boolean message, CallbackInfoReturnable<Boolean> cir) {
        if (player == null && Guards.allowWorldEffect((Claim) (Object) this, permission)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setPlayerGroup", at = @At("HEAD"), cancellable = true, remap = false)
    private void flanwarfare$guardMembers(UUID player, String group, boolean force,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (Guards.blockManagement((Claim) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setHomePos", at = @At("HEAD"), cancellable = true, remap = false)
    private void flanwarfare$guardHome(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (Guards.blockManagement((Claim) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setClaimName", at = @At("HEAD"), cancellable = true, remap = false)
    private void flanwarfare$guardName(String name, CallbackInfo ci) {
        if (Guards.blockManagement((Claim) (Object) this)) {
            ci.cancel();
        }
    }
}
