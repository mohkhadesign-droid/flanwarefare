package net.flanwarfare.flags.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;
import net.minecraft.block.BannerBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MainWindow;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.item.DyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.tileentity.BannerPattern;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Flag Designer (layout after the reference sheet):
 * <pre>
 *  Flag Design
 *  +---------+  [16 dye colours, 2 rows of 8         ]
 *  | rotating|  [pattern grid, 4 rows of 8           ]
 *  |   3D    |
 *  | preview |
 *  +---------+
 *  [pattern item | Banner Pattern Item ... unlocked item patterns]
 *  [base][layer1]..[layer6]                 [<] [>] [x]
 *  [      Cancel      ]  [      Save Flag      ]
 * </pre>
 * Editing works on a client-side copy; nothing changes in the world until Save Flag, which the
 * server validates. Escape and Cancel discard. Ctrl+Z / Ctrl+Y undo and redo.
 *
 * <p>Selecting the base slot and clicking a colour changes the base colour; selecting a layer
 * and clicking a colour recolours it, clicking a pattern replaces it. With nothing selected a
 * pattern click adds a new layer in the selected colour (at most six, the vanilla loom limit).
 *
 * <p>The preview is the real flag model: the same pole, crossbar, finial and the vanilla banner
 * renderer the world uses, drawn with its own camera. It turns once every 15 s; drag to turn
 * and tilt it, scroll to zoom, double-click (or R) to reset the view.
 */
public final class FlagDesignerScreen extends Screen {
    private static final int W = 300;
    private static final int H = 236;
    private static final List<String> ITEM_PATTERNS = Arrays.asList("cre", "sku", "flo", "moj", "glb", "pig");
    private static final Map<String, Item> PATTERN_ITEMS = new HashMap<>();

    static {
        PATTERN_ITEMS.put("cre", Items.CREEPER_BANNER_PATTERN);
        PATTERN_ITEMS.put("sku", Items.SKULL_BANNER_PATTERN);
        PATTERN_ITEMS.put("flo", Items.FLOWER_BANNER_PATTERN);
        PATTERN_ITEMS.put("moj", Items.MOJANG_BANNER_PATTERN);
        PATTERN_ITEMS.put("glb", Items.GLOBE_BANNER_PATTERN);
        PATTERN_ITEMS.put("pig", Items.PIGLIN_BANNER_PATTERN);
    }

    /** One editable design (base colour + layers). Immutable snapshots go on the undo stacks. */
    private static final class Design {
        DyeColor base;
        final List<Pair<BannerPattern, DyeColor>> layers = new ArrayList<>();

        Design copy() {
            Design d = new Design();
            d.base = base;
            d.layers.addAll(layers);
            return d;
        }
    }

    private final UUID claimId;
    private final long token;
    private final String claimLabel;
    private final List<String> unlockedItemPatterns;
    private final int maxLayers;
    private final List<BannerPattern> gridPatterns = new ArrayList<>();
    private final List<BannerPattern> itemPatterns = new ArrayList<>();
    private final ModelRenderer fabric = FlagRenderer.newFabric();

    private Design design = new Design();
    private final Deque<Design> undo = new ArrayDeque<>();
    private final Deque<Design> redo = new ArrayDeque<>();
    private DyeColor dye = DyeColor.WHITE;
    /** -1 none, 0 base, 1..6 layer. */
    private int selected = -1;
    private boolean saving;
    private String error;

    // Preview camera.
    private float yaw = 25.0F;
    private float pitch = 12.0F;
    private float zoom = 1.0F;
    private boolean dragging;
    private long lastInteraction;
    private long lastFrame;
    private long lastClick;

    // Cached icon stacks.
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    private Button saveButton;
    private int left;
    private int top;

    public FlagDesignerScreen(UUID claimId, long token, String claimLabel, CompoundNBT data, List<String> unlocked, int maxLayers) {
        super(new StringTextComponent("Flag Design"));
        this.claimId = claimId;
        this.token = token;
        this.claimLabel = claimLabel;
        this.unlockedItemPatterns = unlocked;
        this.maxLayers = Math.max(1, Math.min(16, maxLayers));
        design.base = DyeColor.byId(data == null ? 0 : data.getInt("Base"));
        if (data != null && data.contains("Patterns", 9)) {
            ListNBT list = data.getList("Patterns", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundNBT layer = list.getCompound(i);
                BannerPattern p = BannerPattern.byHash(layer.getString("Pattern"));
                if (p != null && p != BannerPattern.BASE) {
                    design.layers.add(Pair.of(p, DyeColor.byId(layer.getInt("Color"))));
                }
            }
        }
        for (BannerPattern p : BannerPattern.values()) {
            if (p == BannerPattern.BASE) {
                continue;
            }
            if (ITEM_PATTERNS.contains(p.getHashname())) {
                itemPatterns.add(p);
            } else {
                gridPatterns.add(p);
            }
        }
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(2, (height - H) / 2);
        addButton(new Button(left + 206, top + 185, 24, 20, new StringTextComponent("<"), b -> moveSelected(-1)));
        addButton(new Button(left + 234, top + 185, 24, 20, new StringTextComponent(">"), b -> moveSelected(1)));
        addButton(new Button(left + 262, top + 185, 30, 20, new StringTextComponent("X"), b -> removeSelected()));
        addButton(new Button(left + 8, top + 211, 140, 20, new StringTextComponent("Cancel"), b -> onClose()));
        saveButton = addButton(new Button(left + 152, top + 211, 140, 20, new StringTextComponent("Save Flag"), b -> save()));
        lastFrame = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ editing

    private void change(Runnable edit) {
        undo.push(design.copy());
        if (undo.size() > 64) {
            undo.removeLast();
        }
        redo.clear();
        edit.run();
        error = null;
    }

    private void moveSelected(int dir) {
        if (selected < 1) {
            return;
        }
        int i = selected - 1;
        int j = i + dir;
        if (j < 0 || j >= design.layers.size()) {
            return;
        }
        change(() -> {
            Pair<BannerPattern, DyeColor> a = design.layers.get(i);
            design.layers.set(i, design.layers.get(j));
            design.layers.set(j, a);
        });
        selected = j + 1;
    }

    private void removeSelected() {
        if (selected < 1 || selected > design.layers.size()) {
            return;
        }
        int i = selected - 1;
        change(() -> design.layers.remove(i));
        selected = -1;
    }

    private void pickColor(DyeColor color) {
        dye = color;
        if (selected == 0) {
            change(() -> design.base = color);
        } else if (selected >= 1 && selected <= design.layers.size()) {
            int i = selected - 1;
            change(() -> design.layers.set(i, Pair.of(design.layers.get(i).getFirst(), color)));
        }
    }

    private void pickPattern(BannerPattern pattern) {
        if (selected >= 1 && selected <= design.layers.size()) {
            int i = selected - 1;
            change(() -> design.layers.set(i, Pair.of(pattern, design.layers.get(i).getSecond())));
            return;
        }
        if (design.layers.size() >= maxLayers) {
            error = "A banner can have at most " + maxLayers + " pattern layers. Remove one first.";
            return;
        }
        change(() -> design.layers.add(Pair.of(pattern, dye)));
    }

    private void save() {
        if (saving) {
            return;
        }
        saving = true;
        error = null;
        saveButton.active = false;
        saveButton.setMessage(new StringTextComponent("Saving..."));
        List<String> hashes = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Pair<BannerPattern, DyeColor> layer : design.layers) {
            hashes.add(layer.getFirst().getHashname());
            colors.add(layer.getSecond().getId());
        }
        DesignerClient.sendSave(claimId, token, design.base.getId(), hashes, colors);
    }

    /** Server answer to Save Flag. Success closes; failure keeps the unsaved work open. */
    void onResult(boolean ok, String message) {
        saving = false;
        saveButton.active = true;
        saveButton.setMessage(new StringTextComponent("Save Flag"));
        if (ok) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendStatusMessage(new StringTextComponent("[War] " + message).mergeStyle(TextFormatting.GREEN), false);
            }
            closeScreen();
        } else {
            error = message;
        }
    }

    // ------------------------------------------------------------------ input

    private boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private int paletteX(int i) {
        return left + 100 + (i % 8) * 24;
    }

    private int paletteY(int i) {
        return top + 20 + (i / 8) * 19;
    }

    private int gridX(int i) {
        return left + 100 + (i % 8) * 24;
    }

    private int gridY(int i) {
        return top + 62 + (i / 8) * 23;
    }

    private int slotX(int i) {
        return left + 10 + i * 27;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < 16; i++) {
                if (in(mx, my, paletteX(i), paletteY(i), 23, 18)) {
                    pickColor(DyeColor.byId(i));
                    return true;
                }
            }
            for (int i = 0; i < gridPatterns.size(); i++) {
                if (in(mx, my, gridX(i), gridY(i), 23, 22)) {
                    pickPattern(gridPatterns.get(i));
                    return true;
                }
            }
            for (int i = 0; i < itemPatterns.size(); i++) {
                BannerPattern p = itemPatterns.get(i);
                if (in(mx, my, left + 176 + i * 19, top + 161, 18, 18)) {
                    if (unlockedItemPatterns.contains(p.getHashname())) {
                        pickPattern(p);
                    } else {
                        error = "Carry a " + new ItemStack(PATTERN_ITEMS.get(p.getHashname())).getDisplayName().getString()
                                + " to use this pattern.";
                    }
                    return true;
                }
            }
            for (int i = 0; i <= maxLayers; i++) {
                if (in(mx, my, slotX(i), top + 185, 25, 20)) {
                    if (i == 0 || i <= design.layers.size()) {
                        selected = selected == i ? -1 : i;
                    }
                    return true;
                }
            }
            if (in(mx, my, left + 8, top + 20, 84, 132)) {
                long now = System.currentTimeMillis();
                if (now - lastClick < 300L) {
                    resetView();
                }
                lastClick = now;
                dragging = true;
                lastInteraction = now;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            yaw += (float) dx * 1.5F;
            pitch = Math.max(-30.0F, Math.min(50.0F, pitch + (float) dy * 0.8F));
            lastInteraction = System.currentTimeMillis();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (in(mx, my, left + 8, top + 20, 84, 132)) {
            zoom = Math.max(0.6F, Math.min(2.4F, zoom + (float) delta * 0.1F));
            lastInteraction = System.currentTimeMillis();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (hasControlDown() && key == GLFW.GLFW_KEY_Z && !undo.isEmpty()) {
            redo.push(design.copy());
            design = undo.pop();
            selected = -1;
            return true;
        }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Y && !redo.isEmpty()) {
            undo.push(design.copy());
            design = redo.pop();
            selected = -1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_R && !hasControlDown()) {
            resetView();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            removeSelected();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void resetView() {
        yaw = 25.0F;
        pitch = 12.0F;
        zoom = 1.0F;
        lastInteraction = 0L;
    }

    // ------------------------------------------------------------------ drawing helpers

    private List<Pair<BannerPattern, DyeColor>> layersOf(DyeColor base, List<Pair<BannerPattern, DyeColor>> layers) {
        List<Pair<BannerPattern, DyeColor>> list = new ArrayList<>();
        list.add(Pair.of(BannerPattern.BASE, base));
        list.addAll(layers);
        return list;
    }

    /** A banner item stack (cached by content) for 2D icons. */
    private ItemStack icon(DyeColor base, List<Pair<BannerPattern, DyeColor>> layers) {
        StringBuilder key = new StringBuilder(base.getTranslationKey());
        for (Pair<BannerPattern, DyeColor> l : layers) {
            key.append('|').append(l.getFirst().getHashname()).append(':').append(l.getSecond().getId());
        }
        return iconCache.computeIfAbsent(key.toString(), k -> {
            ItemStack stack = new ItemStack(BannerBlock.forColor(base));
            if (!layers.isEmpty()) {
                ListNBT patterns = new ListNBT();
                for (Pair<BannerPattern, DyeColor> l : layers) {
                    CompoundNBT tag = new CompoundNBT();
                    tag.putString("Pattern", l.getFirst().getHashname());
                    tag.putInt("Color", l.getSecond().getId());
                    patterns.add(tag);
                }
                stack.getOrCreateChildTag("BlockEntityTag").put("Patterns", patterns);
            }
            return stack;
        });
    }

    private static DyeColor contrast(DyeColor color) {
        return color == DyeColor.WHITE || color == DyeColor.YELLOW || color == DyeColor.LIME
                || color == DyeColor.LIGHT_GRAY || color == DyeColor.PINK ? DyeColor.GRAY : DyeColor.RED;
    }

    private void frame(MatrixStack ms, int x, int y, int w, int h, boolean highlight) {
        fill(ms, x, y, x + w, y + h, highlight ? 0xFFF0F0F0 : 0xFF2A2C31);
        fill(ms, x + 1, y + 1, x + w - 1, y + h - 1, 0xFF17181C);
    }

    private void panel(MatrixStack ms, int x, int y, int w, int h) {
        fill(ms, x, y, x + w, y + h, 0xFF2B2D33);
        fill(ms, x + 1, y + 1, x + w - 1, y + h - 1, 0xFF1E2025);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        renderBackground(ms);
        long now = System.currentTimeMillis();
        float dt = Math.min(100L, now - lastFrame) / 1000.0F;
        lastFrame = now;
        if (!dragging && now - lastInteraction > 2500L) {
            yaw += dt * 24.0F; // 360 degrees in 15 s
        }

        // Window
        fill(ms, left - 2, top - 2, left + W + 2, top + H + 2, 0xFF0E0F12);
        fill(ms, left - 1, top - 1, left + W + 1, top + H + 1, 0xFF5E626B);
        fill(ms, left, top, left + W, top + H, 0xFF26282E);
        font.drawStringWithShadow(ms, "Flag Design", left + 8, top + 7, 0xFFFFFFFF);
        String sub = claimLabel;
        font.drawString(ms, sub, left + W - 8 - font.getStringWidth(sub), top + 7, 0xFF9AA0AA);

        // Preview
        panel(ms, left + 7, top + 19, 86, 134);
        renderPreview(ms, left + 8, top + 20, 84, 132);

        // Palette
        panel(ms, left + 98, top + 18, 196, 41);
        for (int i = 0; i < 16; i++) {
            DyeColor c = DyeColor.byId(i);
            int x = paletteX(i);
            int y = paletteY(i);
            boolean chosen = c == dye;
            fill(ms, x, y, x + 23, y + 18, chosen ? 0xFFFFFFFF : 0xFF111215);
            int rgb = 0xFF000000 | c.getColorValue();
            fill(ms, x + 1, y + 1, x + 22, y + 17, rgb);
            fill(ms, x + 1, y + 1, x + 22, y + 3, 0x30FFFFFF);
            fill(ms, x + 1, y + 15, x + 22, y + 17, 0x30000000);
        }

        // Pattern grid (icons show the pattern in the selected dye on a contrasting base)
        panel(ms, left + 98, top + 60, 196, 95);
        DyeColor iconBase = dye == design.base ? contrast(dye) : design.base;
        BannerPattern hovered = null;
        for (int i = 0; i < gridPatterns.size(); i++) {
            int x = gridX(i);
            int y = gridY(i);
            boolean over = in(mouseX, mouseY, x, y, 23, 22);
            frame(ms, x, y, 23, 22, over);
            if (over) {
                hovered = gridPatterns.get(i);
            }
        }
        for (int i = 0; i < gridPatterns.size(); i++) {
            itemRenderer.renderItemIntoGUI(icon(iconBase, java.util.Collections.singletonList(Pair.of(gridPatterns.get(i), dye))),
                    gridX(i) + 4, gridY(i) + 3);
        }

        // Banner pattern items
        panel(ms, left + 7, top + 157, 286, 24);
        frame(ms, left + 10, top + 160, 20, 18, false);
        String firstUnlocked = unlockedItemPatterns.isEmpty() ? "cre" : unlockedItemPatterns.get(0);
        itemRenderer.renderItemIntoGUI(new ItemStack(PATTERN_ITEMS.getOrDefault(firstUnlocked, Items.CREEPER_BANNER_PATTERN)),
                left + 12, top + 161);
        font.drawString(ms, "Banner Pattern Item", left + 34, top + 160, 0xFFFFFFFF);
        font.drawString(ms, unlockedItemPatterns.isEmpty() ? "Carry a pattern item to unlock" : "Unlocked by items you carry",
                left + 34, top + 170, 0xFF8E949E);
        for (int i = 0; i < itemPatterns.size(); i++) {
            BannerPattern p = itemPatterns.get(i);
            int x = left + 176 + i * 19;
            boolean ok = unlockedItemPatterns.contains(p.getHashname());
            boolean over = in(mouseX, mouseY, x, top + 161, 18, 18);
            frame(ms, x, top + 160, 18, 20, over && ok);
            if (over) {
                hovered = p;
            }
        }
        for (int i = 0; i < itemPatterns.size(); i++) {
            BannerPattern p = itemPatterns.get(i);
            boolean ok = unlockedItemPatterns.contains(p.getHashname());
            int x = left + 176 + i * 19;
            if (ok) {
                itemRenderer.renderItemIntoGUI(icon(iconBase, java.util.Collections.singletonList(Pair.of(p, dye))), x + 1, top + 162);
            } else {
                itemRenderer.renderItemIntoGUI(new ItemStack(PATTERN_ITEMS.get(p.getHashname())), x + 1, top + 162);
            }
        }
        for (int i = 0; i < itemPatterns.size(); i++) {
            if (!unlockedItemPatterns.contains(itemPatterns.get(i).getHashname())) {
                int x = left + 176 + i * 19;
                RenderSystem.disableDepthTest();
                fill(ms, x + 1, top + 161, x + 17, top + 179, 0xA0101114); // locked: greyed out
                RenderSystem.enableDepthTest();
            }
        }

        // Layers: base + up to six patterns, in application order
        panel(ms, left + 7, top + 183, 196, 24);
        for (int i = 0; i <= maxLayers && i < 7; i++) {
            int x = slotX(i);
            boolean filled = i == 0 || i <= design.layers.size();
            frame(ms, x, top + 185, 25, 20, selected == i);
        }
        itemRenderer.renderItemIntoGUI(icon(design.base, java.util.Collections.<Pair<BannerPattern, DyeColor>>emptyList()),
                slotX(0) + 4, top + 187);
        for (int i = 0; i < design.layers.size() && i < 6; i++) {
            Pair<BannerPattern, DyeColor> l = design.layers.get(i);
            DyeColor b = l.getSecond() == design.base ? contrast(l.getSecond()) : design.base;
            itemRenderer.renderItemIntoGUI(icon(b, java.util.Collections.singletonList(l)), slotX(i + 1) + 4, top + 187);
        }

        super.render(ms, mouseX, mouseY, partialTicks);

        if (error != null) {
            String e = error;
            while (font.getStringWidth(e) > W - 16 && e.length() > 4) {
                e = e.substring(0, e.length() - 4) + "...";
            }
            fill(ms, left + 8, top + H - 37, left + W - 8, top + H - 27, 0xC0300000);
            font.drawString(ms, e, left + 10, top + H - 36, 0xFFFF6B6B);
        }

        // Tooltips
        if (hovered != null) {
            boolean locked = ITEM_PATTERNS.contains(hovered.getHashname()) && !unlockedItemPatterns.contains(hovered.getHashname());
            if (locked) {
                renderTooltip(ms, new ItemStack(PATTERN_ITEMS.get(hovered.getHashname())).getDisplayName(), mouseX, mouseY);
            } else {
                renderTooltip(ms, new TranslationTextComponent("block.minecraft.banner." + hovered.getFileName() + "."
                        + dye.getTranslationKey()), mouseX, mouseY);
            }
        } else {
            for (int i = 0; i < 16; i++) {
                if (in(mouseX, mouseY, paletteX(i), paletteY(i), 23, 18)) {
                    renderTooltip(ms, new TranslationTextComponent("color.minecraft." + DyeColor.byId(i).getTranslationKey()),
                            mouseX, mouseY);
                }
            }
            for (int i = 1; i <= design.layers.size() && i <= 6; i++) {
                if (in(mouseX, mouseY, slotX(i), top + 185, 25, 20)) {
                    Pair<BannerPattern, DyeColor> l = design.layers.get(i - 1);
                    renderTooltip(ms, new StringTextComponent(i + ". ").appendSibling(new TranslationTextComponent(
                            "block.minecraft.banner." + l.getFirst().getFileName() + "." + l.getSecond().getTranslationKey())),
                            mouseX, mouseY);
                }
            }
            if (in(mouseX, mouseY, slotX(0), top + 185, 25, 20)) {
                renderTooltip(ms, new StringTextComponent("Base: ").appendSibling(
                        new TranslationTextComponent("color.minecraft." + design.base.getTranslationKey())), mouseX, mouseY);
            }
        }
    }

    /**
     * The rotating 3D flag: upper pole, crossbar, finial and the banner, drawn with the world
     * renderer's own geometry and the vanilla banner pattern renderer, clipped to the preview.
     */
    private void renderPreview(MatrixStack ms, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        MainWindow window = mc.getMainWindow();
        double scale = window.getGuiScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * scale), (int) (window.getFramebufferHeight() - (y + h) * scale),
                (int) (w * scale), (int) (h * scale));

        float px = h / 3.9F * zoom; // GUI pixels per block
        ms.push();
        ms.translate(x + w / 2.0D, y + h / 2.0D, 300.0D);
        ms.scale(px, -px, px); // same handedness trick as vanilla's GUI item renderer
        ms.rotate(Vector3f.XP.rotationDegrees(pitch));
        ms.rotate(Vector3f.YP.rotationDegrees(yaw));
        ms.translate(-0.5D, -70.0D / 16.0D, -9.0D / 16.0D);
        RenderHelper.setupGui3DDiffuseLighting();
        IRenderTypeBuffer.Impl buffers = mc.getRenderTypeBuffers().getBufferSource();
        int light = 0xF000F0;
        FlagRenderer.drawStructure(ms, buffers, light, OverlayTexture.NO_OVERLAY, 40.0F, true);
        FlagRenderer.drawFabric(ms, buffers, light, OverlayTexture.NO_OVERLAY, fabric, 90.0F,
                layersOf(design.base, design.layers));
        buffers.finish();
        ms.pop();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
