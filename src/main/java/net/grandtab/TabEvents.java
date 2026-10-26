package net.grandtab;

import net.grandtab.command.TabCommand;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

/** Forge events the mod listens to. */
public final class TabEvents {

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        GrandTab.store().load();
        TabService service = new TabService(event.getServer(), GrandTab.store());
        GrandTab.setService(service);
        service.start();
        GrandTab.LOG.info("Grand Tab List {} ready: {} kingdoms, {} roles, {} assigned players, logo mode {}",
                GrandTab.version(), service.config().kingdoms.size(), service.config().roles.size(),
                GrandTab.store().assignments().size(), service.config().header.logoMode);
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        TabService service = GrandTab.service();
        if (service != null) {
            service.stop();
        }
        GrandTab.setService(null);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        TabService service = GrandTab.service();
        if (event.phase == TickEvent.Phase.END && service != null) {
            service.tick();
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        TabCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        TabService service = GrandTab.service();
        if (service != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.onLogin((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TabService service = GrandTab.service();
        if (service != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.onLogout((ServerPlayerEntity) event.getPlayer());
        }
    }

    /** Respawning creates a new player object; make sure its tab name is set again. */
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        TabService service = GrandTab.service();
        if (service != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.onLogin((ServerPlayerEntity) event.getPlayer());
        }
    }

    /**
     * Sets the tab-list name. LOWEST priority, so this mod's name is the one shown even if
     * another mod also formats tab names (otherwise prefixes could appear twice).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTabListName(PlayerEvent.TabListNameFormat event) {
        TabService service = GrandTab.service();
        if (service == null || !(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }
        ITextComponent name = service.displayName((ServerPlayerEntity) event.getPlayer());
        if (name != null) {
            event.setDisplayName(name);
        }
    }
}
