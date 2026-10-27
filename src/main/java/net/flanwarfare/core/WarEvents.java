package net.flanwarfare.core;

import io.github.flemmli97.flan.api.forge.PermissionCheckEvent;
import net.flanwarfare.command.FlagCommand;
import net.flanwarfare.command.StatsCommand;
import net.flanwarfare.command.RankCommand;
import net.flanwarfare.command.WarCommand;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.protection.ExplosionRules;
import net.flanwarfare.protection.FlagProtection;
import net.flanwarfare.protection.RaidPermissions;
import net.flanwarfare.ranks.RankDisplay;
import net.flanwarfare.ranks.RankRenderer;
import net.flanwarfare.ranks.RankService;
import net.flanwarfare.util.Msg;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.PistonEvent;
import net.minecraftforge.event.world.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

import java.util.List;

/** Every Forge event the add-on listens to, delegating to the services. */
public final class WarEvents {
    private final RaidPermissions permissions = new RaidPermissions();
    private final FlagProtection protection = new FlagProtection();
    private final ExplosionRules explosions = new ExplosionRules();

    /** LOWEST so Flan (which loads claims while worlds are created) is completely ready. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerStarted(FMLServerStartedEvent event) {
        WarCore.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        WarCore.stop();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        WarCore core = WarCore.get();
        if (core != null) {
            core.tick();
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FlagCommand.register(event.getDispatcher());
        WarCommand.register(event.getDispatcher());
        WarCommand.registerRoot(event.getDispatcher());
        StatsCommand.register(event.getDispatcher());
        RankCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPermissionCheck(PermissionCheckEvent event) {
        permissions.onPermissionCheck(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreak(BlockEvent.BreakEvent event) {
        protection.onBreak(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        protection.onPlace(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        protection.onFluidPlace(event);
    }

    /** Before Flan's own (normal priority) filtering: record what caused the explosion. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onExplosionStart(ExplosionEvent.Detonate event) {
        explosions.begin(event);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onExplosion(ExplosionEvent.Detonate event) {
        protection.onExplosion(event);
    }

    /** After Flan and flag protection: entity rule, raid alerts, clear the context. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosionEnd(ExplosionEvent.Detonate event) {
        explosions.end(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPiston(PistonEvent.Pre event) {
        protection.onPiston(event);
    }

    /**
     * Vanilla and Forge decide the post-sleep time through this event; a raid window clamps it.
     * Beds stay usable (spawn points are set as normal); only the time jump is limited.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onSleepFinished(SleepFinishedTimeEvent event) {
        WarCore core = WarCore.ready();
        if (core == null || !WarConfig.PREVENT_SLEEP_SKIP.get() || !(event.getWorld() instanceof ServerWorld)) {
            return;
        }
        ServerWorld world = (ServerWorld) event.getWorld();
        if (world.getDimensionKey() != World.OVERWORLD) {
            return;
        }
        long now = world.getDayTime();
        long target = event.getNewTime();
        long clamped = core.raid().clampSleep(now, target);
        if (clamped != target && event.setTimeAddition(clamped)) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                Msg.send(player, Msg.warn(core.raid().isActive()
                        ? "A raid is active: sleeping cannot skip the night."
                        : "Sleeping stopped at the start of the raid window."));
            }
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        WarCore core = WarCore.ready();
        if (core == null || !(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        Msg.send(player, Msg.info(core.raid().statusLine()));
        core.alerts().loginSummary(player);
        core.members().loginDelivery(player);
        List<ClaimRecord> pending = core.lifecycle().pendingOf(player.getUniqueID());
        if (!pending.isEmpty()) {
            Msg.send(player, Msg.warn("You have " + pending.size() + " inactive claim(s) without a flag. "
                    + "They protect nothing until you stand inside and run /flag place."));
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WarCore core = WarCore.ready();
        if (core != null && event.getPlayer() instanceof ServerPlayerEntity) {
            core.rankDisplay().onLogout((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        WarCore core = WarCore.ready();
        if (core != null && event.getPlayer() instanceof ServerPlayerEntity) {
            core.rankDisplay().onPlayerChanged((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        WarCore core = WarCore.ready();
        if (core != null && event.getPlayer() instanceof ServerPlayerEntity) {
            core.rankDisplay().onPlayerChanged((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        WarCore core = WarCore.ready();
        if (core != null && !event.isCanceled() && event.getEntityLiving() instanceof ServerPlayerEntity) {
            core.rankDisplay().onPlayerChanged((ServerPlayerEntity) event.getEntityLiving());
        }
    }

    /**
     * Rank label entities only exist while this server session tracks them. One loaded from a
     * chunk or player file (left by a crash) is refused, also before the add-on is ready.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote() || !event.getEntity().getTags().contains(RankDisplay.ENTITY_TAG)) {
            return;
        }
        WarCore core = WarCore.get();
        if (core == null || core.rankDisplay().isStray(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        WarCore core = WarCore.ready();
        if (core == null || !WarConfig.RANK_CHAT_ENABLED.get() || event.getPlayer() == null) {
            return;
        }
        RankService.Active active = core.ranks().active(event.getPlayer().getUniqueID());
        if (active != null) {
            event.setComponent(RankRenderer.chat(active.rank, core.rankDisplay().phase(),
                    event.getUsername(), event.getMessage()));
        }
    }
}
