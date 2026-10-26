package net.flanwarfare.flags.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.BannerBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.DyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The raid capture panel (client only). Flan Warfare sends, per viewer, the shared capture
 * fraction plus that viewer's perspective (attacker / defender / neutral) and the capture trend;
 * this class only draws it:
 *
 * <pre>
 *  [banner] ATTACKER NAME      STATUS >>      DEFENDER NAME [banner]
 *           [=========== bar ==========-------]  62%
 *                  explanatory subtitle
 * </pre>
 * Attacker always left, defender always right. Green = good for the viewer, red = bad, gold =
 * contested (paused, striped). The fill is left-anchored and exactly fraction x track width for
 * everyone; when the progress drops, a faded trail shows the lost part for 1.5 s.
 */
public final class CaptureHudClient {
    private static final int GREEN = 0xFF3BD13B;
    private static final int GREEN_HI = 0xFF7BEA7B;
    private static final int RED = 0xFFE83A3A;
    private static final int RED_HI = 0xFFFF7A7A;
    private static final int GOLD = 0xFFF2B21B;
    private static final int GOLD_DARK = 0xFFC8870C;
    private static final int NEUTRAL = 0xFFD0D0D0;
    private static final long TIMEOUT_MS = 5000L;
    private static final long TRAIL_MS = 1500L;

    private static final class Hud {
        int perspective; // 0 attacker, 1 defender, 2 neutral
        int trend;       // 0 increasing, 1 contested, 2 decreasing
        float fraction;
        String attacker = "";
        String defender = "";
        ItemStack attackerBanner = ItemStack.EMPTY;
        ItemStack defenderBanner = ItemStack.EMPTY;
        long updated;
        float trailFrom = -1.0F;
        long trailStart;
    }

    private final Map<UUID, Hud> huds = new HashMap<>();

    // ------------------------------------------------------------------ network (from Flan Warfare)

    public void onPayload(NetworkEvent.ServerCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        PacketBuffer buf = event.getPayload();
        int type = buf.readByte();
        UUID id = buf.readUniqueId();
        if (type == 1) {
            ctx.enqueueWork(() -> huds.remove(id));
        } else if (type == 0) {
            int perspective = buf.readByte();
            int trend = buf.readByte();
            float fraction = buf.readFloat();
            String attacker = buf.readString(64);
            String defender = buf.readString(64);
            CompoundNBT attackerBanner = buf.readCompoundTag();
            CompoundNBT defenderBanner = buf.readCompoundTag();
            ctx.enqueueWork(() -> {
                Hud hud = huds.computeIfAbsent(id, k -> new Hud());
                long now = System.currentTimeMillis();
                if (hud.updated > 0 && fraction < hud.fraction - 1e-4F) {
                    // Progress fell: keep the highest recent value as a fading trail.
                    boolean trailAlive = hud.trailFrom >= 0 && now - hud.trailStart < TRAIL_MS;
                    hud.trailFrom = trailAlive ? Math.max(hud.trailFrom, hud.fraction) : hud.fraction;
                    hud.trailStart = now;
                }
                hud.perspective = perspective;
                hud.trend = trend;
                hud.fraction = Math.max(0.0F, Math.min(1.0F, fraction));
                hud.attacker = attacker;
                hud.defender = defender;
                hud.attackerBanner = banner(attackerBanner);
                hud.defenderBanner = banner(defenderBanner);
                hud.updated = now;
            });
        }
        ctx.setPacketHandled(true);
    }

    private static ItemStack banner(CompoundNBT tag) {
        if (tag == null) {
            return ItemStack.EMPTY;
        }
        DyeColor color = DyeColor.byId(tag.getInt("Base"));
        AbstractBannerBlock block = (AbstractBannerBlock) BannerBlock.forColor(color);
        ItemStack stack = new ItemStack(block.asItem());
        if (tag.contains("Patterns", 9) && !tag.getList("Patterns", 10).isEmpty()) {
            CompoundNBT entity = new CompoundNBT();
            entity.put("Patterns", tag.getList("Patterns", 10).copy());
            stack.getOrCreateTag().put("BlockEntityTag", entity);
        }
        return stack;
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        huds.clear();
    }

    // ------------------------------------------------------------------ presentation rules

    private static int tone(int perspective, int trend) {
        if (trend == 1) {
            return GOLD;
        }
        if (perspective == 2) {
            return NEUTRAL;
        }
        boolean attackerGains = trend == 0;
        return (perspective == 0) == attackerGains ? GREEN : RED;
    }

    private static String status(int perspective, int trend) {
        if (trend == 1) {
            return "CONTESTED";
        }
        if (perspective == 0) {
            return trend == 0 ? "CAPTURING" : "LOSING PROGRESS";
        }
        if (perspective == 1) {
            return trend == 0 ? "LOSING CLAIM" : "DEFENDING";
        }
        return trend == 0 ? "CAPTURE IN PROGRESS" : "CAPTURE FADING";
    }

    private static String subtitle(int perspective, int trend) {
        if (trend == 1) {
            return "Capture progress paused";
        }
        String dir = trend == 0 ? "increasing" : "decreasing";
        if (perspective == 0) {
            return "Your capture progress is " + dir;
        }
        if (perspective == 1) {
            return "Enemy capture progress is " + dir;
        }
        return "Capture progress is " + dir;
    }

    // ------------------------------------------------------------------ drawing

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || huds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        huds.values().removeIf(h -> now - h.updated > TIMEOUT_MS);
        Hud shown = null;
        for (Hud h : huds.values()) {
            // The viewer's own fights first, then the most recently updated.
            if (shown == null || (h.perspective != 2 && shown.perspective == 2)
                    || ((h.perspective != 2) == (shown.perspective != 2) && h.updated > shown.updated)) {
                shown = h;
            }
        }
        if (shown != null) {
            draw(event.getMatrixStack(), shown, event.getWindow().getScaledWidth(), now);
        }
    }

    private static void fill(MatrixStack ms, int x0, int y0, int x1, int y1, int color) {
        AbstractGui.fill(ms, x0, y0, x1, y1, color);
    }

    private void draw(MatrixStack ms, Hud hud, int screenWidth, long now) {
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.fontRenderer;
        int w = Math.min(280, screenWidth - 8);
        int h = 46;
        int x0 = (screenWidth - w) / 2;
        int y0 = 4;
        int x1 = x0 + w;
        int y1 = y0 + h;
        int tone = tone(hud.perspective, hud.trend);

        // Panel: dark body, silver bevel, gold corners.
        fill(ms, x0, y0, x1, y1, 0xE0181A1F);
        fill(ms, x0, y0, x1, y0 + 1, 0xFFA9AEB8);
        fill(ms, x0, y1 - 1, x1, y1, 0xFF5B6069);
        fill(ms, x0, y0, x0 + 1, y1, 0xFF8E939C);
        fill(ms, x1 - 1, y0, x1, y1, 0xFF6A6F78);
        fill(ms, x0 + 1, y0 + 1, x1 - 1, y0 + 2, 0xFF2E3138);
        corners(ms, x0, y0, x1, y1);

        // Banner frames at both ends.
        int frameW = 20;
        bannerFrame(ms, mc, x0 + 4, y0 + 4, frameW, h - 8, hud.attackerBanner);
        bannerFrame(ms, mc, x1 - 4 - frameW, y0 + 4, frameW, h - 8, hud.defenderBanner);

        int cx0 = x0 + 4 + frameW + 6;
        int cx1 = x1 - 4 - frameW - 6;
        int centre = (cx0 + cx1) / 2;

        // Row 1: attacker name (left), status + arrows (centre), defender name (right).
        String status = "§l" + status(hud.perspective, hud.trend);
        int statusW = font.getStringWidth(status) + 12;
        int nameRoom = Math.max(10, (cx1 - cx0 - statusW) / 2 - 6);
        int rowY = y0 + 6;
        font.drawStringWithShadow(ms, trim(font, hud.attacker, nameRoom), cx0, rowY, 0xFFE8E8E8);
        String def = trim(font, hud.defender, nameRoom);
        font.drawStringWithShadow(ms, def, cx1 - font.getStringWidth(def), rowY, 0xFFE8E8E8);
        int sx = centre - statusW / 2;
        font.drawStringWithShadow(ms, status, sx, rowY, tone);
        arrows(ms, sx + statusW - 10, rowY, hud.trend, tone);

        // Row 2: bar (left-anchored fill) and percentage.
        int percent = (int) Math.floor(hud.fraction * 100.0F);
        String pct = percent + "%";
        int barX0 = cx0;
        int barX1 = cx1 - font.getStringWidth("100%") - 6;
        int barY0 = y0 + 19;
        int barY1 = barY0 + 8;
        fill(ms, barX0 - 1, barY0 - 1, barX1 + 1, barY1 + 1, 0xFF60656E);
        fill(ms, barX0, barY0, barX1, barY1, 0xFF23262C);
        int trackW = barX1 - barX0;
        int filled = Math.round(hud.fraction * trackW);
        if (hud.trailFrom > hud.fraction && now - hud.trailStart < TRAIL_MS) {
            float alpha = 1.0F - (now - hud.trailStart) / (float) TRAIL_MS;
            int trailEnd = Math.round(hud.trailFrom * trackW);
            int trailColor = hud.perspective == 0 ? RED : hud.perspective == 1 ? GREEN : NEUTRAL;
            int a = (int) (0x70 * alpha) << 24;
            fill(ms, barX0 + filled, barY0, barX0 + trailEnd, barY1, a | (trailColor & 0xFFFFFF));
        }
        if (filled > 0) {
            if (hud.trend == 1) {
                // Contested: gold with subtle diagonal stripes.
                for (int y = barY0; y < barY1; y++) {
                    for (int x = barX0; x < barX0 + filled; x += 3) {
                        boolean dark = (((x - barX0) + (y - barY0)) / 3) % 2 == 0;
                        fill(ms, x, y, Math.min(barX0 + filled, x + 3), y + 1, dark ? GOLD_DARK : GOLD);
                    }
                }
            } else {
                fill(ms, barX0, barY0, barX0 + filled, barY1, tone);
                int hi = tone == GREEN ? GREEN_HI : tone == RED ? RED_HI : 0xFFF0F0F0;
                fill(ms, barX0, barY0, barX0 + filled, barY0 + 2, hi);
            }
        }
        font.drawStringWithShadow(ms, pct, barX1 + 5, barY0, 0xFFFFFFFF);

        // Row 3: subtitle.
        String sub = trim(font, subtitle(hud.perspective, hud.trend), cx1 - cx0);
        font.drawStringWithShadow(ms, sub, centre - font.getStringWidth(sub) / 2.0F, y0 + 32, 0xFFC8CCD2);
    }

    private static void corners(MatrixStack ms, int x0, int y0, int x1, int y1) {
        int gold = 0xFFE3B341;
        fill(ms, x0, y0, x0 + 5, y0 + 2, gold);
        fill(ms, x0, y0, x0 + 2, y0 + 5, gold);
        fill(ms, x1 - 5, y0, x1, y0 + 2, gold);
        fill(ms, x1 - 2, y0, x1, y0 + 5, gold);
        fill(ms, x0, y1 - 2, x0 + 5, y1, gold);
        fill(ms, x0, y1 - 5, x0 + 2, y1, gold);
        fill(ms, x1 - 5, y1 - 2, x1, y1, gold);
        fill(ms, x1 - 2, y1 - 5, x1, y1, gold);
    }

    private static void bannerFrame(MatrixStack ms, Minecraft mc, int x, int y, int w, int h, ItemStack banner) {
        fill(ms, x, y, x + w, y + h, 0xFF3C4048);
        fill(ms, x + 1, y + 1, x + w - 1, y + h - 1, 0xFF14161A);
        fill(ms, x, y, x + 3, y + 1, 0xFFE3B341);
        fill(ms, x + w - 3, y + h - 1, x + w, y + h, 0xFFE3B341);
        if (!banner.isEmpty()) {
            // The real banner item (its colour and patterns), centred in the frame.
            mc.getItemRenderer().renderItemIntoGUI(banner, x + (w - 16) / 2, y + (h - 16) / 2);
        }
    }

    /** Right arrows (increasing), pause bars (contested) or left arrows (decreasing), 7 px tall. */
    private static void arrows(MatrixStack ms, int x, int y, int trend, int color) {
        if (trend == 1) {
            fill(ms, x + 1, y, x + 3, y + 7, color);
            fill(ms, x + 5, y, x + 7, y + 7, color);
            return;
        }
        for (int k = 0; k < 2; k++) {
            int bx = x + k * 5;
            for (int i = 0; i < 4; i++) {
                int len = i + 1; // middle row (i = 3) is the tip
                if (trend == 0) {
                    fill(ms, bx, y + i, bx + len, y + i + 1, color);
                    fill(ms, bx, y + 6 - i, bx + len, y + 7 - i, color);
                } else {
                    fill(ms, bx + 4 - len, y + i, bx + 4, y + i + 1, color);
                    fill(ms, bx + 4 - len, y + 6 - i, bx + 4, y + 7 - i, color);
                }
            }
        }
    }

    /** Shortens text with "..." so long kingdom names never run into the status. */
    private static String trim(FontRenderer font, String text, int width) {
        if (font.getStringWidth(text) <= width) {
            return text;
        }
        String dots = "...";
        int room = width - font.getStringWidth(dots);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.getStringWidth(sb.toString() + c) > room) {
                break;
            }
            sb.append(c);
        }
        return sb + dots;
    }
}
