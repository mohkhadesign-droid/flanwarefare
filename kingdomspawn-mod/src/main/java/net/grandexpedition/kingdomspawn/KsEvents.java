package net.grandexpedition.kingdomspawn;

import net.grandexpedition.kingdomspawn.command.KsCommands;
import net.grandexpedition.kingdomspawn.search.BiomeSearch;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

/** Forge events. Nothing here touches Flan, beds, respawn anchors or the world spawn. */
public final class KsEvents {
    private int saveTimer;

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        KingdomSpawn.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        KingdomSpawn.stop();
    }

    /** LOWEST: registered after other mods, so our /spawn wins if another mod also has one. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRegisterCommands(RegisterCommandsEvent event) {
        KsCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || KingdomSpawn.server() == null || KingdomSpawn.data() == null) {
            return;
        }
        BiomeSearch search = KingdomSpawn.search();
        if (search != null) {
            search.tick();
        }
        KingdomSpawn.deployer().tick(KingdomSpawn.server());
        KingdomSpawn.teleports().tick(KingdomSpawn.server());
        if (++saveTimer >= 600) {
            saveTimer = 0;
            KingdomSpawn.data().saveIfDirty();
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity && KingdomSpawn.data() != null) {
            KingdomSpawn.deployer().onLogin((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity) {
            KingdomSpawn.teleports().onLogout((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public void onHurt(LivingHurtEvent event) {
        if (event.getEntityLiving() instanceof ServerPlayerEntity && event.getAmount() > 0) {
            KingdomSpawn.teleports().onHurt((ServerPlayerEntity) event.getEntityLiving(), event.getSource());
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof ServerPlayerEntity && KingdomSpawn.data() != null) {
            KingdomSpawn.teleports().onDeath((ServerPlayerEntity) event.getEntityLiving());
        }
    }
}
