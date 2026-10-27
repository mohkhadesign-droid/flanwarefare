package net.flanwarfare.flags.client;

import net.flanwarfare.flags.FlagsMod;
import net.minecraft.client.gui.ScreenManager;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only registration (never loaded on a dedicated server). */
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init(IEventBus bus) {
        bus.addListener((FMLClientSetupEvent event) -> {
            ClientRegistry.bindTileEntityRenderer(FlagsMod.FLAG_TILE.get(), FlagRenderer::new);
            event.enqueueWork(() -> ScreenManager.registerFactory(FlagsMod.DESIGNER_CONTAINER.get(), FlagDesignerScreen::new));
        });
        CaptureHudClient hud = new CaptureHudClient();
        FlagsMod.HUD_CHANNEL.addListener(hud::onPayload);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(hud);
        FlagsMod.DESIGNER_CHANNEL.addListener(DesignerClient::onPayload);
    }
}
