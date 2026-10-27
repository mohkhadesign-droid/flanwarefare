package net.flanwarfare.mixin;

import com.mojang.brigadier.context.CommandContext;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.commands.CommandClaim;
import net.flanwarfare.flan.SubclaimCommands;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code /flan group add|remove} and {@code /flan group players add|remove} respect
 * subclaim edit mode in Flan 1.7.2 (see {@link SubclaimCommands}). Each method has exactly one
 * {@code ClaimStorage.getClaimAt} call (the claim at the player's feet). It is redirected to
 * return the subclaim there when the player is in subclaim mode.
 */
@Mixin(value = CommandClaim.class, remap = false)
public abstract class CommandClaimMixin {

    @Inject(method = "modifyGroup", at = @At("HEAD"), remap = false)
    private static void flanwarfare$groupEditor(CommandContext<CommandSource> ctx, boolean remove,
                                                CallbackInfoReturnable<Integer> cir) {
        SubclaimCommands.begin(ctx.getSource().getEntity() instanceof ServerPlayerEntity
                ? (ServerPlayerEntity) ctx.getSource().getEntity() : null);
    }

    @Inject(method = "modifyPlayer", at = @At("HEAD"), remap = false)
    private static void flanwarfare$playerEditor(CommandContext<CommandSource> ctx, String group, boolean remove,
                                                 CallbackInfoReturnable<Integer> cir) {
        SubclaimCommands.begin(ctx.getSource().getEntity() instanceof ServerPlayerEntity
                ? (ServerPlayerEntity) ctx.getSource().getEntity() : null);
    }

    @Redirect(method = {"modifyGroup", "modifyPlayer"},
            at = @At(value = "INVOKE",
                    target = "Lio/github/flemmli97/flan/claim/ClaimStorage;getClaimAt(Lnet/minecraft/util/math/BlockPos;)Lio/github/flemmli97/flan/claim/Claim;"),
            remap = false)
    private static Claim flanwarfare$subclaimAware(ClaimStorage storage, BlockPos pos) {
        return SubclaimCommands.pick(storage.getClaimAt(pos), pos);
    }
}
