package io.github.flemmli97.flan.claim;

import io.github.flemmli97.flan.player.EnumEditMode;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.Collection;
import java.util.UUID;

/** Compile-time signature stub of Flan 1.7.2. Never packaged. */
public class ClaimStorage {
    private ClaimStorage() {
        throw new UnsupportedOperationException("stub");
    }

    public static ClaimStorage get(ServerWorld world) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean createClaim(BlockPos pos1, BlockPos pos2, ServerPlayerEntity player) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean deleteClaim(Claim claim, boolean updateClaim, EnumEditMode mode, ServerWorld world) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean resizeClaim(Claim claim, BlockPos from, BlockPos to, ServerPlayerEntity player) {
        throw new UnsupportedOperationException("stub");
    }

    public Claim getClaimAt(BlockPos pos) {
        throw new UnsupportedOperationException("stub");
    }

    public Claim getFromUUID(UUID uuid) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean transferOwner(Claim claim, ServerPlayerEntity player, UUID newOwner) {
        throw new UnsupportedOperationException("stub");
    }

    public Collection<Claim> allClaimsFromPlayer(UUID player) {
        throw new UnsupportedOperationException("stub");
    }

    public Collection<Claim> getAdminClaims() {
        throw new UnsupportedOperationException("stub");
    }

    public void save(MinecraftServer server, RegistryKey<World> dimension) {
        throw new UnsupportedOperationException("stub");
    }
}
