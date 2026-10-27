package net.flanwarfare.flan;

import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.player.EnumEditMode;
import io.github.flemmli97.flan.player.PlayerClaimData;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Backport of Flan 1.8.4's fix "/flan group ignored subclaim mode" (Flemmli97/Flan@82124199e1)
 * for Flan 1.7.2.
 *
 * <p>In Flan 1.7.2, {@code /flan permission ...} and {@code /flan info} respect subclaim edit mode,
 * but {@code /flan group add|remove <group>} ({@code CommandClaim.modifyGroup}) and
 * {@code /flan group players add|remove <group> <players>} ({@code CommandClaim.modifyPlayer})
 * always act on the main claim (verified in the 1.7.2 bytecode). So, in subclaim mode, the
 * permissions went to the subclaim's group while the players went to a group of the same name
 * on the main claim, and the subclaim's group stayed empty.
 *
 * <p>{@code CommandClaimMixin} sends both commands' claim lookup through {@link #pick}: in
 * subclaim mode, standing inside a subclaim, they edit that subclaim, exactly as Flan 1.8.4
 * does. Everything else is unchanged: Flan's own permission checks (EDITPERMS, the owner),
 * storage and saving.
 */
public final class SubclaimCommands {
    private static final ThreadLocal<ServerPlayerEntity> EDITOR = new ThreadLocal<>();

    private SubclaimCommands() {
    }

    /** Called at the start of modifyGroup/modifyPlayer with the command's player (or null). */
    public static void begin(ServerPlayerEntity player) {
        EDITOR.set(player);
    }

    /** The claim the command should edit: the subclaim here in subclaim mode, else the claim. */
    public static Claim pick(Claim claim, BlockPos pos) {
        ServerPlayerEntity player = EDITOR.get();
        EDITOR.remove();
        if (claim == null || player == null || pos == null) {
            return claim;
        }
        if (PlayerClaimData.get(player).getEditMode() == EnumEditMode.SUBCLAIM) {
            Claim sub = claim.getSubClaim(pos);
            if (sub != null) {
                return sub;
            }
        }
        return claim;
    }
}
