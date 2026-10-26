package net.flanwarfare.flags.client;

import net.flanwarfare.flags.FlagsMod;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only registration (never loaded on a dedicated server). */
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init(IEventBus bus) {
        bus.addListener((FMLClientSetupEvent event) ->
                ClientRegistry.bindTileEntityRenderer(FlagsMod.FLAG_TILE.get(), FlagRenderer::new));
    }
}
