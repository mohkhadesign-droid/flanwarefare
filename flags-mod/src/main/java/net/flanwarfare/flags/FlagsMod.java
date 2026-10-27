package net.flanwarfare.flags;

import net.minecraft.block.Block;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.tileentity.TileEntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.extensions.IForgeContainerType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Flan Warfare Flags: the claim-flag block and its model.
 *
 * <p>Installed on the server and on every client. It registers one block,
 * {@code flanwarfareflags:flag}, and its tile entity. Flan Warfare & Conquest (server-only)
 * builds flags out of this block when this mod is installed: the bottom block carries the
 * tile entity with the banner design and the raise height, five blocks above it are the
 * invisible upper pole. All rendering code lives in the {@code client} package and is only
 * loaded on clients.
 */
@Mod(FlagsMod.MOD_ID)
public final class FlagsMod {
    public static final String MOD_ID = "flanwarfareflags";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<TileEntityType<?>> TILES = DeferredRegister.create(ForgeRegistries.TILE_ENTITIES, MOD_ID);

    public static final DeferredRegister<ContainerType<?>> CONTAINERS = DeferredRegister.create(ForgeRegistries.CONTAINERS, MOD_ID);

    public static final RegistryObject<FlagBlock> FLAG = BLOCKS.register("flag", FlagBlock::new);
    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<TileEntityType<FlagTileEntity>> FLAG_TILE = TILES.register("flag",
            () -> TileEntityType.Builder.create(FlagTileEntity::new, FLAG.get()).build(null));

    /** The Flag Designer menu (opened by Flan Warfare by this registry name: flanwarfareflags:designer). */
    public static final RegistryObject<ContainerType<FlagDesignerContainer>> DESIGNER_CONTAINER = CONTAINERS.register("designer",
            () -> IForgeContainerType.create(FlagDesignerContainer::new));

    /**
     * Capture HUD data from Flan Warfare (server). Registered on both sides so the Forge
     * handshake matches; only clients listen. The server sends plain custom-payload packets.
     */
    public static final net.minecraftforge.fml.network.event.EventNetworkChannel HUD_CHANNEL =
            net.minecraftforge.fml.network.NetworkRegistry.newEventChannel(
                    new net.minecraft.util.ResourceLocation(MOD_ID, "hud"), () -> "1", v -> true, v -> true);

    /** Flag Designer: server -> client save results (opening goes through the container). */
    public static final net.minecraftforge.fml.network.event.EventNetworkChannel DESIGNER_CHANNEL =
            net.minecraftforge.fml.network.NetworkRegistry.newEventChannel(
                    new net.minecraft.util.ResourceLocation(MOD_ID, "designer"), () -> "1", v -> true, v -> true);

    public FlagsMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        TILES.register(bus);
        CONTAINERS.register(bus);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.flanwarfare.flags.client.ClientSetup.init(bus));
    }
}
