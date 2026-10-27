package net.flanwarfare.designer.client;

import net.flanwarfare.designer.DesignerMod;
import net.minecraft.client.gui.ScreenManager;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only registration (never loaded on a dedicated server). */
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init(IEventBus bus) {
        bus.addListener((FMLClientSetupEvent event) ->
                event.enqueueWork(() -> ScreenManager.registerFactory(DesignerMod.DESIGNER_CONTAINER.get(), FlagDesignerScreen::new)));
        DesignerMod.RESULT_CHANNEL.addListener(DesignerClient::onPayload);
    }
}
