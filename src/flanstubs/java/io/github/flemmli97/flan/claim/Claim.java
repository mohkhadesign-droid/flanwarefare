package io.github.flemmli97.flan.claim;

import com.google.gson.JsonObject;
import io.github.flemmli97.flan.api.permission.ClaimPermission;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.Effect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.server.ServerWorld;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Compile-time signature stub of Flan 1.7.2. Never packaged. */
public class Claim {
    private Claim() {
        throw new UnsupportedOperationException("stub");
    }

    public UUID getClaimID() {
        throw new UnsupportedOperationException("stub");
    }

    public String getClaimName() {
        throw new UnsupportedOperationException("stub");
    }

    public void setClaimName(String name) {
        throw new UnsupportedOperationException("stub");
    }

    public UUID getOwner() {
        throw new UnsupportedOperationException("stub");
    }

    public ServerWorld getWorld() {
        throw new UnsupportedOperationException("stub");
    }

    public Claim parentClaim() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean isAdminClaim() {
        throw new UnsupportedOperationException("stub");
    }

    public int getPlane() {
        throw new UnsupportedOperationException("stub");
    }

    /** {minX, maxX, minZ, maxZ, minY}. */
    public int[] getDimensions() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean insideClaim(BlockPos pos) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean isRemoved() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean canInteract(ServerPlayerEntity player, ClaimPermission perm, BlockPos pos, boolean message) {
        throw new UnsupportedOperationException("stub");
    }

    public int permEnabled(ClaimPermission perm) {
        throw new UnsupportedOperationException("stub");
    }

    public Claim getSubClaim(BlockPos pos) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean editPerms(ServerPlayerEntity player, String group, ClaimPermission perm, int mode, boolean griefPrevention) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean removePermGroup(ServerPlayerEntity player, String group) {
        throw new UnsupportedOperationException("stub");
    }

    public Set<Claim> tryCreateSubClaim(BlockPos pos1, BlockPos pos2) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean deleteSubClaim(Claim sub) {
        throw new UnsupportedOperationException("stub");
    }

    public List<Claim> getAllSubclaims() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean setPlayerGroup(UUID player, String group, boolean force) {
        throw new UnsupportedOperationException("stub");
    }

    public int groupHasPerm(String group, ClaimPermission perm) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean setHomePos(BlockPos pos) {
        throw new UnsupportedOperationException("stub");
    }

    public BlockPos getHomePos() {
        throw new UnsupportedOperationException("stub");
    }

    public void removePotion(Effect effect) {
        throw new UnsupportedOperationException("stub");
    }

    public Map<Effect, Integer> getPotions() {
        throw new UnsupportedOperationException("stub");
    }

    public void setEnterTitle(ITextComponent title, ITextComponent sub) {
        throw new UnsupportedOperationException("stub");
    }

    public void setLeaveTitle(ITextComponent title, ITextComponent sub) {
        throw new UnsupportedOperationException("stub");
    }

    public void setDirty(boolean dirty) {
        throw new UnsupportedOperationException("stub");
    }

    public JsonObject toJson(JsonObject obj) {
        throw new UnsupportedOperationException("stub");
    }
}
