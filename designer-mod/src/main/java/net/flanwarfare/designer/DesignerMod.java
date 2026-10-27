package net.flanwarfare.designer;

import net.minecraft.block.Block;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.extensions.IForgeContainerType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.event.EventNetworkChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Flan Warfare Designer: the Flag Designer block and its editor, as a separate optional mod.
 *
 * <p>Install it on the server and on every client, next to Flan Warfare (server). Players craft
 * the Flag Designer, place it inside their claim and right-click it. Flan Warfare checks who
 * may edit, opens the menu and applies a saved design to that claim's flag. Removing this mod
 * removes only the designer; claims, flags and designs are untouched.
 */
@Mod(DesignerMod.MOD_ID)
public final class DesignerMod {
    public static final String MOD_ID = "flanwarfaredesigner";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<ContainerType<?>> CONTAINERS = DeferredRegister.create(ForgeRegistries.CONTAINERS, MOD_ID);

    public static final RegistryObject<FlagDesignerBlock> FLAG_DESIGNER = BLOCKS.register("flag_designer", FlagDesignerBlock::new);
    public static final RegistryObject<Item> FLAG_DESIGNER_ITEM = ITEMS.register("flag_designer",
            () -> new BlockItem(FLAG_DESIGNER.get(), new Item.Properties().group(ItemGroup.DECORATIONS)));

    /** The designer menu, opened by Flan Warfare by this registry name: flanwarfaredesigner:designer. */
    public static final RegistryObject<ContainerType<FlagDesignerContainer>> DESIGNER_CONTAINER = CONTAINERS.register("designer",
            () -> IForgeContainerType.create(FlagDesignerContainer::new));

    /** Server -> client: save results. Registered on both sides so the handshake matches; clients listen. */
    public static final EventNetworkChannel RESULT_CHANNEL = NetworkRegistry.newEventChannel(
            new ResourceLocation(MOD_ID, "designer"), () -> "1", v -> true, v -> true);

    public DesignerMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        CONTAINERS.register(bus);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.flanwarfare.designer.client.ClientSetup.init(bus));
    }
}
