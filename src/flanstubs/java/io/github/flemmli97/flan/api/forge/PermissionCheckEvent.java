package io.github.flemmli97.flan.api.forge;

import io.github.flemmli97.flan.api.permission.ClaimPermission;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.eventbus.api.Event;

/** Compile-time signature stub of Flan 1.7.2. Never packaged; the real class comes from Flan. */
public class PermissionCheckEvent extends Event {
    public final ServerPlayerEntity player;
    public final ClaimPermission permission;
    public final BlockPos pos;

    public PermissionCheckEvent(ServerPlayerEntity player, ClaimPermission permission, BlockPos pos) {
        throw new UnsupportedOperationException("stub");
    }

    public ActionResultType getActionResult() {
        throw new UnsupportedOperationException("stub");
    }

    public void setResult(ActionResultType result) {
        throw new UnsupportedOperationException("stub");
    }
}
