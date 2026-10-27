package net.flanwarfare.designer;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.BannerPatternItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;

import java.util.UUID;

/**
 * The Flag Designer's container (menu). It exists so the <b>Banner Pattern Item</b> slot is a
 * real, server-authoritative inventory slot, synchronised by vanilla's container code. That
 * rules out duplication and desynchronisation in a way a client-only "slot" could not.
 *
 * <ul>
 *   <li>Slot 0: the Banner Pattern Item slot. It accepts only banner pattern items (vanilla's
 *   or any mod's {@link BannerPatternItem}), one at a time.</li>
 *   <li>Slots 1..36: the player's inventory (27 main slots then the 9 hotbar slots). The
 *   designer has no room to draw them, so they sit off-screen. The screen moves items between
 *   them and slot 0 only with vanilla shift-click (quick move) requests, which the server
 *   carries out.</li>
 * </ul>
 *
 * <p>Like the loom, the item is <b>not consumed</b>. Whatever sits in slot 0 goes back to the
 * player's inventory when the designer closes for any reason (Cancel, Escape, a successful
 * save, death, disconnect). If the inventory is full it drops at the player's feet, as vanilla
 * does for crafting grids.
 *
 * <p>Flan Warfare (server) opens this container ({@code flanwarfaredesigner:designer}) by registry name and sends the opening data
 * (claim, token, current design) as the container's extra data. On a dedicated server the
 * data is null; the server keeps its own session.
 */
public final class FlagDesignerContainer extends Container {
    public static final int PATTERN_SLOT = 0;
    /** Slot position inside the designer screen (must match FlagDesignerScreen's layout). */
    public static final int PATTERN_SLOT_X = 269;
    public static final int PATTERN_SLOT_Y = 91;
    private static final int PLAYER_FIRST = 1;
    private static final int PLAYER_END = 37; // exclusive

    /** Opening data, client side only. */
    public static final class OpenData {
        public final UUID claimId;
        public final long token;
        public final String label;
        public final CompoundNBT design;
        public final int maxLayers;

        OpenData(PacketBuffer buf) {
            claimId = buf.readUniqueId();
            token = buf.readLong();
            label = buf.readString(64);
            CompoundNBT tag = buf.readCompoundTag();
            design = tag == null ? new CompoundNBT() : tag;
            maxLayers = Math.max(1, Math.min(16, buf.readVarInt()));
        }
    }

    private final Inventory patternInventory = new Inventory(1);
    private final OpenData data;

    public FlagDesignerContainer(int windowId, PlayerInventory playerInventory, PacketBuffer extra) {
        super(DesignerMod.DESIGNER_CONTAINER.get(), windowId);
        addSlot(new Slot(patternInventory, 0, PATTERN_SLOT_X, PATTERN_SLOT_Y) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return stack.getItem() instanceof BannerPatternItem;
            }

            @Override
            public int getSlotStackLimit() {
                return 1;
            }
        });
        for (int i = 9; i < 36; i++) {
            addSlot(new Slot(playerInventory, i, -10000, -10000));
        }
        for (int i = 0; i < 9; i++) {
            addSlot(new Slot(playerInventory, i, -10000, -10000));
        }
        data = extra == null ? null : new OpenData(extra);
    }

    /** Client only: what the server sent when opening. Null on the server. */
    public OpenData data() {
        return data;
    }

    /** The item in the Banner Pattern Item slot (empty if none). */
    public ItemStack patternItem() {
        return getSlot(PATTERN_SLOT).getStack();
    }

    @Override
    public boolean canInteractWith(PlayerEntity player) {
        return player.isAlive();
    }

    /** Shift-click: inventory -> pattern slot (pattern items only), pattern slot -> inventory. */
    @Override
    public ItemStack transferStackInSlot(PlayerEntity player, int index) {
        if (index < 0 || index >= inventorySlots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();
        ItemStack before = stack.copy();
        if (index == PATTERN_SLOT) {
            if (!mergeItemStack(stack, PLAYER_FIRST, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!(stack.getItem() instanceof BannerPatternItem) || getSlot(PATTERN_SLOT).getHasStack()) {
                return ItemStack.EMPTY;
            }
            if (!mergeItemStack(stack, PATTERN_SLOT, PATTERN_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }
        if (stack.getCount() == before.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return before;
    }

    /** Never consumes the pattern item: it goes back to the player (vanilla loom behaviour). */
    @Override
    public void onContainerClosed(PlayerEntity player) {
        super.onContainerClosed(player);
        clearContainer(player, player.world, patternInventory);
    }
}
