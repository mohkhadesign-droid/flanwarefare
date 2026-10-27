package net.flanwarfare.flags.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;
import net.flanwarfare.flags.FlagDesignerContainer;
import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.Atlases;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.client.renderer.model.RenderMaterial;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.InputMappings;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.BannerPatternItem;
import net.minecraft.item.DyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.tileentity.BannerPattern;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Flag Designer, laid out after the reference mockup.
 * <pre>
 *  +--------------------------------------------------------------------+
 *  | Flag Design                                                  label |
 *  | +----------+ +-- 1. Choose Color ------------[Base][Pattern]-----+ |
 *  | |          | | 16 dye swatches, two rows of eight                | |
 *  | |  3D flag | +-- 2. Select Pattern --------------+-+-- item ----+ |
 *  | |  preview | | scrollable, clipped 8 x 3 grid    |#| [slot]     | |
 *  | |          | |                                   |#|   v        | |
 *  | |          | |                                   | | [pattern]  | |
 *  | |          | +-- 3. Pattern Layers (n/6) -----------------------+ |
 *  | |  drag/zoom| | [Base] [1][2][3][4][5][6]        [up][down][del] | |
 *  | +----------+ +---------------------------------------------------+ |
 *  | [          Cancel          ]  [          Save Flag           ]      |
 *  +--------------------------------------------------------------------+
 * </pre>
 * Every component has fixed bounds inside the 312 x 234 window. Minecraft keeps the scaled
 * screen at least 320 x 240 at every GUI scale, so the window always fits without rescaling.
 * Drawing and input use the same rectangles. A click goes to exactly one component and is
 * never passed through to anything underneath (vanilla slot handling included), so dragging or
 * scrolling cannot press a button.
 *
 * <p><b>Editing</b> is on a client-side copy. Nothing reaches the server until Save Flag.
 * Cancel, Escape and the inventory key close without saving (the pattern item goes back to the
 * inventory). Ctrl+Z / Ctrl+Y undo and redo.
 *
 * <p><b>Banner Pattern Item slot</b>: a real container slot synchronised by the server. Clicking
 * the empty slot moves a banner pattern item from the inventory into it (a list appears if
 * there are several). Clicking a filled slot moves the item back. Both are vanilla quick-move
 * requests. The item is never consumed.
 */
public final class FlagDesignerScreen extends ContainerScreen<FlagDesignerContainer> {
    private static final int W = 312;
    private static final int H = 234;

    // Layout, relative to the window's top-left corner.
    private static final int PREVIEW_X = 7, PREVIEW_Y = 20, PREVIEW_W = 90, PREVIEW_H = 172;
    private static final int RESET_X = 85, RESET_Y = 194, RESET_W = 11, RESET_H = 10;
    private static final int SWATCH_X = 105, SWATCH_Y = 33, SWATCH_W = 23, SWATCH_H = 14, SWATCH_STEP_X = 25, SWATCH_STEP_Y = 15;
    private static final int TAB_BASE_X = 226, TAB_PATTERN_X = 258, TAB_Y = 21, TAB_W = 30, TAB_PATTERN_W = 44, TAB_H = 10;
    private static final int GRID_X = 106, GRID_Y = 80, CELL_W = 16, CELL_H = 26, STEP_X = 17, STEP_Y = 27, COLS = 8, ROWS = 3;
    private static final int GRID_W = COLS * STEP_X - 1, GRID_H = ROWS * STEP_Y - 1;
    private static final int BAR_X = 242, BAR_W = 3;
    private static final int ITEM_X = 250, ITEM_Y = 69, ITEM_W = 54, ITEM_H = 92;
    private static final int RESULT_X = 269, RESULT_Y = 121;
    private static final int BASE_SLOT_X = 106, LAYER_X = 130, LAYER_STEP = 19, LAYER_Y = 178, LAYER_W = 18, LAYER_H = 25;
    private static final int UP_X = 248, DOWN_X = 267, REMOVE_X = 286, TOOL_Y = 180, TOOL_S = 17;
    private static final int CANCEL_X = 6, SAVE_X = 158, FOOT_Y = 210, CANCEL_W = 147, SAVE_W = 148, FOOT_H = 20;
    private static final int POPUP_X = 104, POPUP_Y = 72, POPUP_W = 138, POPUP_H = 90, POPUP_COLS = 7;

    private enum Target { BASE, PATTERN }

    private enum Drag { NONE, PREVIEW, SCROLLBAR }

    /** One editable design. Snapshots of it are the undo and redo history. */
    private static final class Design {
        DyeColor base = DyeColor.WHITE;
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
    private final int maxLayers;
    /** Pattern id -> banner pattern item that unlocks it (vanilla's six and installed mods'). */
    private final Map<String, Item> itemLocked = new HashMap<>();
    /** Item-locked patterns already on this flag when it was opened (the server allows those). */
    private final Set<String> alreadyOnFlag = new HashSet<>();
    private final List<BannerPattern> gridPatterns = new ArrayList<>();
    private final Map<BannerPattern, TextureAtlasSprite> sprites = new HashMap<>();
    private final ModelRenderer fabric = FlagRenderer.newFabric();

    private Design design = new Design();
    private final Deque<Design> undo = new ArrayDeque<>();
    private final Deque<Design> redo = new ArrayDeque<>();
    private Target target = Target.BASE;
    private DyeColor dye = DyeColor.BLACK;
    private int selected = -1; // selected pattern layer index, -1 none
    private int scroll;        // first visible grid row
    private boolean popup;
    private String notice;
    private boolean saving;
    private long savingSince;

    private float yaw = 25.0F;
    private float pitch = 10.0F;
    private float zoom = 1.0F;
    private Drag drag = Drag.NONE;
    private long lastInteraction;
    private long lastFrame;
    private long lastPreviewClick;

    public FlagDesignerScreen(FlagDesignerContainer container, PlayerInventory inventory, ITextComponent title) {
        super(container, inventory, title);
        xSize = W;
        ySize = H;
        FlagDesignerContainer.OpenData data = container.data();
        claimId = data == null ? null : data.claimId;
        token = data == null ? 0L : data.token;
        claimLabel = data == null ? "" : data.label;
        maxLayers = data == null ? 6 : Math.min(6, data.maxLayers);
        for (Item item : ForgeRegistries.ITEMS) {
            if (item instanceof BannerPatternItem) {
                itemLocked.putIfAbsent(((BannerPatternItem) item).getBannerPattern().getHashname(), item);
            }
        }
        for (BannerPattern p : BannerPattern.values()) {
            if (p != BannerPattern.BASE && !itemLocked.containsKey(p.getHashname())) {
                gridPatterns.add(p);
            }
        }
        if (data != null) {
            CompoundNBT tag = data.design;
            design.base = DyeColor.byId(tag.getInt("Base"));
            ListNBT list = tag.getList("Patterns", 10);
            for (int i = 0; i < list.size() && design.layers.size() < 16; i++) {
                CompoundNBT layer = list.getCompound(i);
                BannerPattern p = BannerPattern.byHash(layer.getString("Pattern"));
                if (p != null && p != BannerPattern.BASE) {
                    design.layers.add(Pair.of(p, DyeColor.byId(layer.getInt("Color"))));
                    if (itemLocked.containsKey(p.getHashname())) {
                        alreadyOnFlag.add(p.getHashname());
                    }
                }
            }
        } else {
            notice = "The designer did not receive the flag data. Close it and left-click the flag again.";
        }
        dye = design.base == DyeColor.BLACK ? DyeColor.WHITE : DyeColor.BLACK;
    }

    @Override
    protected void init() {
        super.init(); // computes guiLeft/guiTop from xSize/ySize (centred)
        lastFrame = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =================================================================== editing

    private void change(Runnable edit) {
        undo.push(design.copy());
        while (undo.size() > 64) {
            undo.removeLast();
        }
        redo.clear();
        edit.run();
        notice = null;
    }

    private void pickColor(DyeColor color) {
        if (target == Target.BASE) {
            if (design.base != color) {
                change(() -> design.base = color);
            }
            return;
        }
        dye = color;
        if (selected >= 0 && selected < design.layers.size() && design.layers.get(selected).getSecond() != color) {
            int i = selected;
            change(() -> design.layers.set(i, Pair.of(design.layers.get(i).getFirst(), color)));
        }
    }

    private void pickPattern(BannerPattern pattern) {
        target = Target.PATTERN;
        if (selected >= 0 && selected < design.layers.size()) {
            int i = selected;
            change(() -> design.layers.set(i, Pair.of(pattern, design.layers.get(i).getSecond())));
            return;
        }
        if (design.layers.size() >= maxLayers) {
            notice = "A banner can have at most " + maxLayers + " pattern layers. Select a layer to replace it, or remove one.";
            return;
        }
        DyeColor color = dye;
        change(() -> design.layers.add(Pair.of(pattern, color)));
    }

    private void selectLayer(int index) {
        if (index == selected || index < 0 || index >= design.layers.size()) {
            selected = -1;
            return;
        }
        selected = index;
        target = Target.PATTERN;
        dye = design.layers.get(index).getSecond();
    }

    private void moveSelected(int direction) {
        int i = selected;
        int j = i + direction;
        if (i < 0 || i >= design.layers.size() || j < 0 || j >= design.layers.size()) {
            return;
        }
        change(() -> Collections.swap(design.layers, i, j));
        selected = j;
    }

    private void removeSelected() {
        int i = selected;
        if (i < 0 || i >= design.layers.size()) {
            return;
        }
        change(() -> design.layers.remove(i));
        selected = -1;
    }

    private void undo() {
        if (!undo.isEmpty()) {
            redo.push(design.copy());
            design = undo.pop();
            selected = -1;
            notice = null;
        }
    }

    private void redo() {
        if (!redo.isEmpty()) {
            undo.push(design.copy());
            design = redo.pop();
            selected = -1;
            notice = null;
        }
    }

    private BannerPattern slotPattern() {
        ItemStack stack = container.patternItem();
        return stack.getItem() instanceof BannerPatternItem ? ((BannerPatternItem) stack.getItem()).getBannerPattern() : null;
    }

    private void save() {
        if (saving) {
            return;
        }
        if (claimId == null) {
            notice = "The designer did not receive the flag data. Close it and left-click the flag again.";
            return;
        }
        BannerPattern fromSlot = slotPattern();
        List<String> hashes = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Pair<BannerPattern, DyeColor> layer : design.layers) {
            String h = layer.getFirst().getHashname();
            if (itemLocked.containsKey(h) && !alreadyOnFlag.contains(h) && (fromSlot == null || !fromSlot.getHashname().equals(h))) {
                notice = "Put the " + new ItemStack(itemLocked.get(h)).getDisplayName().getString() + " in the Banner Pattern Item slot to use "
                        + patternName(layer.getFirst(), layer.getSecond()).getString() + ".";
                return;
            }
            hashes.add(h);
            colors.add(layer.getSecond().getId());
        }
        String problem = DesignerClient.sendSave(claimId, token, design.base.getId(), hashes, colors);
        if (problem != null) {
            notice = problem;
            return;
        }
        saving = true;
        savingSince = System.currentTimeMillis();
        notice = null;
    }

    /** Server answer to Save Flag. Success closes the designer; failure keeps the edit open. */
    void onResult(boolean ok, String message) {
        saving = false;
        if (ok) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendStatusMessage(new StringTextComponent("[War] " + message).mergeStyle(TextFormatting.GREEN), false);
            }
            closeScreen();
        } else {
            notice = message;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (saving && System.currentTimeMillis() - savingSince > 10000L) {
            saving = false;
            notice = "The server did not answer. Your design is still here; try Save Flag again.";
        }
        if (popup && patternItemSlots().isEmpty()) {
            popup = false;
        }
    }

    /** Container slots (1..36) holding a banner pattern item, client view. */
    private List<Slot> patternItemSlots() {
        List<Slot> list = new ArrayList<>();
        for (int i = 1; i < container.inventorySlots.size(); i++) {
            Slot slot = container.inventorySlots.get(i);
            if (slot.getStack().getItem() instanceof BannerPatternItem) {
                list.add(slot);
            }
        }
        return list;
    }

    private void quickMove(Slot slot) {
        handleMouseClick(slot, slot.slotNumber, 0, ClickType.QUICK_MOVE);
    }

    private void clickPatternSlot() {
        Slot slot = container.getSlot(FlagDesignerContainer.PATTERN_SLOT);
        if (slot.getHasStack()) {
            quickMove(slot); // back into the inventory (the server does the move)
            return;
        }
        List<Slot> candidates = patternItemSlots();
        if (candidates.isEmpty()) {
            notice = "You are not carrying any banner pattern item (Creeper Charge, Skull Charge, Flower Charge, Thing, Globe, Snout, or one from a mod).";
        } else if (candidates.size() == 1) {
            quickMove(candidates.get(0));
        } else {
            popup = true;
        }
    }

    private void click() {
        if (minecraft != null) {
            minecraft.getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    // =================================================================== input

    private boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= guiLeft + x && my >= guiTop + y && mx < guiLeft + x + w && my < guiTop + y + h;
    }

    private int rows() {
        return (gridPatterns.size() + COLS - 1) / COLS;
    }

    private int maxScroll() {
        return Math.max(0, rows() - ROWS);
    }

    /** Index of the grid pattern under the mouse (only inside the visible viewport), or -1. */
    private int gridIndexAt(double mx, double my) {
        if (!in(mx, my, GRID_X, GRID_Y, GRID_W, GRID_H)) {
            return -1;
        }
        int dx = (int) mx - guiLeft - GRID_X;
        int dy = (int) my - guiTop - GRID_Y;
        if (dx % STEP_X >= CELL_W || dy % STEP_Y >= CELL_H) {
            return -1;
        }
        int index = (dy / STEP_Y + scroll) * COLS + dx / STEP_X;
        return index < gridPatterns.size() ? index : -1;
    }

    private int swatchAt(double mx, double my) {
        for (int i = 0; i < 16; i++) {
            if (in(mx, my, SWATCH_X + (i % 8) * SWATCH_STEP_X, SWATCH_Y + (i / 8) * SWATCH_STEP_Y, SWATCH_W, SWATCH_H)) {
                return i;
            }
        }
        return -1;
    }

    private int layerAt(double mx, double my) {
        for (int i = 0; i < maxLayers; i++) {
            if (in(mx, my, LAYER_X + i * LAYER_STEP, LAYER_Y, LAYER_W, LAYER_H)) {
                return i;
            }
        }
        return -1;
    }

    private int popupIndexAt(double mx, double my, int count) {
        for (int i = 0; i < count && i < 28; i++) {
            if (in(mx, my, POPUP_X + 6 + (i % POPUP_COLS) * 18, POPUP_Y + 16 + (i / POPUP_COLS) * 18, 18, 18)) {
                return i;
            }
        }
        return -1;
    }

    private void setScrollFromBar(double my) {
        int max = maxScroll();
        if (max == 0) {
            scroll = 0;
            return;
        }
        double t = (my - guiTop - GRID_Y) / (double) GRID_H;
        scroll = Math.max(0, Math.min(max, (int) Math.round(t * max)));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean inside = in(mx, my, 0, 0, W, H);
        if (button != 0) {
            return inside;
        }
        // Footer buttons are always available, whatever is open above them.
        if (in(mx, my, CANCEL_X, FOOT_Y, CANCEL_W, FOOT_H)) {
            click();
            closeScreen();
            return true;
        }
        if (in(mx, my, SAVE_X, FOOT_Y, SAVE_W, FOOT_H)) {
            if (!saving) {
                click();
                save();
            }
            return true;
        }
        if (popup) {
            List<Slot> candidates = patternItemSlots();
            int i = popupIndexAt(mx, my, candidates.size());
            if (i >= 0) {
                quickMove(candidates.get(i));
                popup = false;
            } else if (!in(mx, my, POPUP_X, POPUP_Y, POPUP_W, POPUP_H)) {
                popup = false;
            }
            return true;
        }
        if (in(mx, my, RESET_X, RESET_Y, RESET_W, RESET_H)) {
            click();
            resetView();
            return true;
        }
        if (in(mx, my, PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H)) {
            long now = System.currentTimeMillis();
            if (now - lastPreviewClick < 300L) {
                resetView();
            }
            lastPreviewClick = now;
            drag = Drag.PREVIEW;
            lastInteraction = now;
            return true;
        }
        if (notice != null && in(mx, my, 102, 19, 204, 188)) {
            notice = null; // the first click on the right side only dismisses the message
            return true;
        }
        if (in(mx, my, TAB_BASE_X, TAB_Y, TAB_W, TAB_H)) {
            target = Target.BASE;
            selected = -1;
            return true;
        }
        if (in(mx, my, TAB_PATTERN_X, TAB_Y, TAB_PATTERN_W, TAB_H)) {
            target = Target.PATTERN;
            return true;
        }
        int swatch = swatchAt(mx, my);
        if (swatch >= 0) {
            pickColor(DyeColor.byId(swatch));
            return true;
        }
        if (in(mx, my, BAR_X, GRID_Y, BAR_W, GRID_H)) {
            drag = Drag.SCROLLBAR;
            setScrollFromBar(my);
            return true;
        }
        int index = gridIndexAt(mx, my);
        if (index >= 0) {
            pickPattern(gridPatterns.get(index));
            return true;
        }
        if (in(mx, my, FlagDesignerContainer.PATTERN_SLOT_X - 1, FlagDesignerContainer.PATTERN_SLOT_Y - 1, 18, 18)) {
            clickPatternSlot();
            return true;
        }
        if (in(mx, my, RESULT_X, RESULT_Y, CELL_W, CELL_H)) {
            BannerPattern p = slotPattern();
            if (p != null) {
                pickPattern(p);
            }
            return true;
        }
        if (in(mx, my, BASE_SLOT_X, LAYER_Y, LAYER_W, LAYER_H)) {
            target = Target.BASE;
            selected = -1;
            return true;
        }
        int layer = layerAt(mx, my);
        if (layer >= 0) {
            selectLayer(layer);
            return true;
        }
        if (in(mx, my, UP_X, TOOL_Y, TOOL_S, TOOL_S)) {
            click();
            moveSelected(1);
            return true;
        }
        if (in(mx, my, DOWN_X, TOOL_Y, TOOL_S, TOOL_S)) {
            click();
            moveSelected(-1);
            return true;
        }
        if (in(mx, my, REMOVE_X, TOOL_Y, TOOL_S, TOOL_S)) {
            click();
            removeSelected();
            return true;
        }
        return inside; // inside the window: consumed, never handed to vanilla slot logic
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (drag == Drag.PREVIEW) {
            yaw += (float) dx * 1.5F;
            pitch = Math.max(-30.0F, Math.min(50.0F, pitch + (float) dy * 0.8F));
            lastInteraction = System.currentTimeMillis();
        } else if (drag == Drag.SCROLLBAR) {
            setScrollFromBar(my);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (drag == Drag.PREVIEW) {
            lastInteraction = System.currentTimeMillis();
        }
        drag = Drag.NONE;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (popup) {
            return true;
        }
        if (in(mx, my, GRID_X, GRID_Y, GRID_W + 2 + BAR_W, GRID_H)) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(delta)));
            return true;
        }
        if (in(mx, my, PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H)) {
            zoom = Math.max(0.6F, Math.min(1.8F, zoom + (float) delta * 0.1F));
            lastInteraction = System.currentTimeMillis();
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (popup) {
                popup = false;
            } else {
                closeScreen(); // never saves
            }
            return true;
        }
        if (minecraft != null && minecraft.gameSettings.keyBindInventory.isActiveAndMatches(InputMappings.getInputByCode(key, scan))) {
            closeScreen();
            return true;
        }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Z) {
            undo();
            return true;
        }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Y) {
            redo();
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
        // Everything else is swallowed: no hotbar swaps or item drops through the hidden slots.
        return true;
    }

    private void resetView() {
        yaw = 25.0F;
        pitch = 10.0F;
        zoom = 1.0F;
        lastInteraction = 0L;
    }

    // =================================================================== drawing helpers

    private static DyeColor contrast(DyeColor color) {
        switch (color) {
            case WHITE:
            case YELLOW:
            case LIME:
            case LIGHT_GRAY:
            case PINK:
            case LIGHT_BLUE:
            case MAGENTA:
            case ORANGE:
                return DyeColor.GRAY;
            default:
                return DyeColor.WHITE;
        }
    }

    /** A pattern shown on the current base colour, or on a contrasting one when they match. */
    private DyeColor iconBase(DyeColor patternColor) {
        return patternColor == design.base ? contrast(patternColor) : design.base;
    }

    private ITextComponent patternName(BannerPattern pattern, DyeColor color) {
        String key = "block.minecraft.banner." + pattern.getFileName() + "." + color.getTranslationKey();
        if (I18n.hasKey(key)) {
            return new TranslationTextComponent(key);
        }
        String file = pattern.getFileName();
        int colon = file.indexOf(':');
        file = colon >= 0 ? file.substring(colon + 1) : file;
        StringBuilder pretty = new StringBuilder();
        for (String part : file.replace('/', '_').split("_")) {
            if (!part.isEmpty()) {
                pretty.append(' ').append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return new TranslationTextComponent("color.minecraft." + color.getTranslationKey()).appendString(pretty.toString());
    }

    private TextureAtlasSprite sprite(BannerPattern pattern) {
        return sprites.computeIfAbsent(pattern,
                p -> new RenderMaterial(Atlases.BANNER_ATLAS, p.getTextureLocation(true)).getSprite());
    }

    /**
     * A flat banner face (base plus layers) drawn straight from the banner pattern atlas, tinted
     * like the world renderer. Used for the pattern grid, the layer slots and the item result.
     */
    private void drawFlatBanner(MatrixStack ms, int x, int y, int w, int h, DyeColor base, List<Pair<BannerPattern, DyeColor>> layers) {
        Minecraft mc = Minecraft.getInstance();
        mc.getTextureManager().bindTexture(Atlases.BANNER_ATLAS);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        Matrix4f m = ms.getLast().getMatrix();
        BufferBuilder b = Tessellator.getInstance().getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR_TEX);
        quad(b, m, x, y, w, h, sprite(BannerPattern.BASE), base);
        for (Pair<BannerPattern, DyeColor> layer : layers) {
            quad(b, m, x, y, w, h, sprite(layer.getFirst()), layer.getSecond());
        }
        Tessellator.getInstance().draw();
        RenderSystem.enableDepthTest();
    }

    private static void quad(BufferBuilder b, Matrix4f m, int x, int y, int w, int h, TextureAtlasSprite s, DyeColor color) {
        float[] c = color.getColorComponentValues();
        // The banner front face is pixels (1,1)-(21,41) of the 64x64 pattern texture.
        float u0 = s.getInterpolatedU(1.0D / 4.0D);
        float u1 = s.getInterpolatedU(21.0D / 4.0D);
        float v0 = s.getInterpolatedV(1.0D / 4.0D);
        float v1 = s.getInterpolatedV(41.0D / 4.0D);
        b.pos(m, x, y + h, 0.0F).color(c[0], c[1], c[2], 1.0F).tex(u0, v1).endVertex();
        b.pos(m, x + w, y + h, 0.0F).color(c[0], c[1], c[2], 1.0F).tex(u1, v1).endVertex();
        b.pos(m, x + w, y, 0.0F).color(c[0], c[1], c[2], 1.0F).tex(u1, v0).endVertex();
        b.pos(m, x, y, 0.0F).color(c[0], c[1], c[2], 1.0F).tex(u0, v0).endVertex();
    }

    private void rect(MatrixStack ms, int x, int y, int w, int h, int color) {
        fill(ms, guiLeft + x, guiTop + y, guiLeft + x + w, guiTop + y + h, color);
    }

    /** Raised panel: dark outline, light top-left edge, shaded bottom-right edge. */
    private void panel(MatrixStack ms, int x, int y, int w, int h, int body) {
        rect(ms, x, y, w, h, 0xFF0C0D10);
        rect(ms, x + 1, y + 1, w - 2, h - 2, 0xFF4B4F58);
        rect(ms, x + 2, y + 2, w - 3, h - 3, 0xFF15171B);
        rect(ms, x + 2, y + 2, w - 4, h - 4, body);
    }

    /** Recessed slot: dark top-left, light bottom-right, like vanilla inventory slots. */
    private void slotFrame(MatrixStack ms, int x, int y, int w, int h, boolean highlight, boolean hover) {
        if (highlight) {
            rect(ms, x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF);
        }
        rect(ms, x, y, w, h, 0xFF0B0C0E);
        rect(ms, x + 1, y + 1, w - 1, h - 1, 0xFF5A5E68);
        rect(ms, x + 1, y + 1, w - 2, h - 2, hover ? 0xFF3A3E47 : 0xFF202227);
    }

    private void text(MatrixStack ms, String s, int x, int y, int color) {
        font.drawStringWithShadow(ms, s, guiLeft + x, guiTop + y, color);
    }

    private void smallText(MatrixStack ms, String s, float x, float y, float scale, int color) {
        ms.push();
        ms.translate(guiLeft + x, guiTop + y, 0.0D);
        ms.scale(scale, scale, 1.0F);
        font.drawStringWithShadow(ms, s, 0.0F, 0.0F, color);
        ms.pop();
    }

    private String trim(String s, int width) {
        if (font.getStringWidth(s) <= width) {
            return s;
        }
        while (s.length() > 1 && font.getStringWidth(s + "...") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    private void button(MatrixStack ms, int x, int y, int w, int h, String label, boolean primary, boolean enabled, boolean hover) {
        int top;
        int bottom;
        if (!enabled) {
            top = 0xFF4A4D52;
            bottom = 0xFF3A3C40;
        } else if (primary) {
            top = hover ? 0xFF6FC458 : 0xFF5BB143;
            bottom = hover ? 0xFF3E8A2C : 0xFF327522;
        } else {
            top = hover ? 0xFF9A9EA6 : 0xFF858991;
            bottom = hover ? 0xFF656970 : 0xFF54575D;
        }
        rect(ms, x, y, w, h, 0xFF0B0C0E);
        fillGradient(ms, guiLeft + x + 1, guiTop + y + 1, guiLeft + x + w - 1, guiTop + y + h - 1, top, bottom);
        rect(ms, x + 1, y + 1, w - 2, 1, 0x50FFFFFF);
        rect(ms, x + 1, y + h - 2, w - 2, 1, 0x50000000);
        int color = enabled ? 0xFFFFFFFF : 0xFFA0A0A0;
        font.drawStringWithShadow(ms, label, guiLeft + x + (w - font.getStringWidth(label)) / 2.0F, guiTop + y + (h - 8) / 2.0F, color);
    }

    private void arrowIcon(MatrixStack ms, int x, int y, boolean up, int color) {
        int cx = x + 8;
        for (int r = 0; r < 5; r++) {
            int row = up ? y + 3 + r : y + 13 - r;
            rect(ms, cx - r, row, 2 * r + 1, 1, color);
        }
        rect(ms, cx - 1, up ? y + 8 : y + 4, 3, 5, color);
    }

    private void binIcon(MatrixStack ms, int x, int y, int color) {
        rect(ms, x + 7, y + 3, 3, 1, color);
        rect(ms, x + 4, y + 4, 9, 1, color);
        rect(ms, x + 5, y + 6, 7, 8, color);
        rect(ms, x + 7, y + 7, 1, 6, 0xFF2A2C31);
        rect(ms, x + 9, y + 7, 1, 6, 0xFF2A2C31);
    }

    // =================================================================== rendering

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        renderBackground(ms);
        super.render(ms, mouseX, mouseY, partialTicks); // background layer, slot, (empty) labels
        if (popup) {
            drawPopup(ms, mouseX, mouseY);
        } else if (notice != null) {
            drawNotice(ms);
        }
        if (!popup) {
            renderHoveredTooltip(ms, mouseX, mouseY); // vanilla item tooltip for the pattern slot
            drawTooltips(ms, mouseX, mouseY);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(MatrixStack ms, int mouseX, int mouseY) {
        // Labels are drawn with the panels; no vanilla "Inventory" title.
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(MatrixStack ms, float partialTicks, int mouseX, int mouseY) {
        boolean hoverOk = !popup && drag == Drag.NONE;
        int mx = hoverOk ? mouseX : -10000;
        int my = hoverOk ? mouseY : -10000;
        long now = System.currentTimeMillis();
        float dt = Math.min(100L, now - lastFrame) / 1000.0F;
        lastFrame = now;
        if (drag != Drag.PREVIEW && now - lastInteraction > 2500L) {
            yaw += dt * 24.0F; // one full turn every 15 s
        }

        // Window and title
        rect(ms, -2, -2, W + 4, H + 4, 0xFF050506);
        rect(ms, -1, -1, W + 2, H + 2, 0xFF5D616B);
        rect(ms, 0, 0, W, H, 0xFF1D1F24);
        rect(ms, 0, 0, W, 18, 0xFF15171B);
        rect(ms, 0, 18, W, 1, 0xFF3A3D45);
        ms.push();
        ms.translate(guiLeft + 8, guiTop + 4, 0.0D);
        ms.scale(1.4F, 1.4F, 1.0F);
        font.drawStringWithShadow(ms, "Flag Design", 0.0F, 0.0F, 0xFFFFFFFF);
        ms.pop();
        String label = trim(claimLabel, 150);
        font.drawString(ms, label, guiLeft + W - 8 - font.getStringWidth(label), guiTop + 6, 0xFF9AA0AA);

        drawPreview(ms, mx, my);
        drawColors(ms, mx, my);
        drawPatterns(ms, mx, my);
        drawItemPanel(ms, mx, my);
        drawLayers(ms, mx, my);

        // Footer
        button(ms, CANCEL_X, FOOT_Y, CANCEL_W, FOOT_H, "Cancel", false, true, in(mx, my, CANCEL_X, FOOT_Y, CANCEL_W, FOOT_H));
        button(ms, SAVE_X, FOOT_Y, SAVE_W, FOOT_H, saving ? "Saving..." : "Save Flag", true, !saving,
                in(mx, my, SAVE_X, FOOT_Y, SAVE_W, FOOT_H));
    }

    private void drawPreview(MatrixStack ms, int mx, int my) {
        panel(ms, PREVIEW_X - 1, PREVIEW_Y - 1, PREVIEW_W + 2, 188, 0xFF2A2D33);
        fillGradient(ms, guiLeft + PREVIEW_X, guiTop + PREVIEW_Y, guiLeft + PREVIEW_X + PREVIEW_W, guiTop + PREVIEW_Y + PREVIEW_H,
                0xFF34496B, 0xFF16181D);
        renderModel(ms);
        rect(ms, PREVIEW_X, 193, PREVIEW_W, 12, 0xFF121317);
        smallText(ms, "Drag / Scroll", PREVIEW_X + 4, 196.5F, 0.75F, 0xFFB8BEC8);
        boolean hover = in(mx, my, RESET_X, RESET_Y, RESET_W, RESET_H);
        rect(ms, RESET_X, RESET_Y, RESET_W, RESET_H, hover ? 0xFF5A5E68 : 0xFF33363D);
        font.drawStringWithShadow(ms, "↺", guiLeft + RESET_X + 2.5F, guiTop + RESET_Y + 1.0F, 0xFFFFFFFF);
    }

    /**
     * The rotating 3D flag: upper pole, crossbar, finial and the banner, drawn with the world
     * renderer's own geometry and vanilla's banner pattern renderer, clipped to the preview box
     * with a scissor that follows the GUI scale.
     */
    private void renderModel(MatrixStack ms) {
        Minecraft mc = Minecraft.getInstance();
        MainWindow window = mc.getMainWindow();
        double scale = window.getGuiScaleFactor();
        int x = guiLeft + PREVIEW_X;
        int y = guiTop + PREVIEW_Y;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) Math.floor(x * scale), (int) Math.floor(window.getFramebufferHeight() - (y + PREVIEW_H) * scale),
                (int) Math.ceil(PREVIEW_W * scale), (int) Math.ceil(PREVIEW_H * scale));
        float px = PREVIEW_H / 4.1F * zoom; // GUI pixels per block; at zoom 1 the whole model fits
        ms.push();
        ms.translate(x + PREVIEW_W / 2.0D, y + PREVIEW_H / 2.0D, 300.0D);
        ms.scale(px, -px, px);
        ms.rotate(Vector3f.XP.rotationDegrees(pitch));
        ms.rotate(Vector3f.YP.rotationDegrees(yaw));
        ms.translate(-0.5D, -70.0D / 16.0D, -9.0D / 16.0D);
        RenderHelper.setupGui3DDiffuseLighting();
        IRenderTypeBuffer.Impl buffers = mc.getRenderTypeBuffers().getBufferSource();
        int light = 0xF000F0;
        FlagRenderer.drawStructure(ms, buffers, light, OverlayTexture.NO_OVERLAY, 40.0F, true);
        List<Pair<BannerPattern, DyeColor>> layers = new ArrayList<>();
        layers.add(Pair.of(BannerPattern.BASE, design.base));
        layers.addAll(design.layers);
        FlagRenderer.drawFabric(ms, buffers, light, OverlayTexture.NO_OVERLAY, fabric, 90.0F, layers);
        buffers.finish();
        ms.pop();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void drawColors(MatrixStack ms, int mx, int my) {
        panel(ms, 102, 19, 204, 46, 0xFF2A2D33);
        text(ms, "1. Choose Color", 106, 22, 0xFFFFFFFF);
        boolean baseTab = target == Target.BASE;
        drawTab(ms, TAB_BASE_X, TAB_W, "Base", baseTab, in(mx, my, TAB_BASE_X, TAB_Y, TAB_W, TAB_H));
        drawTab(ms, TAB_PATTERN_X, TAB_PATTERN_W, "Pattern", !baseTab, in(mx, my, TAB_PATTERN_X, TAB_Y, TAB_PATTERN_W, TAB_H));
        DyeColor chosen = baseTab ? design.base : dye;
        for (int i = 0; i < 16; i++) {
            DyeColor c = DyeColor.byId(i);
            int x = SWATCH_X + (i % 8) * SWATCH_STEP_X;
            int y = SWATCH_Y + (i / 8) * SWATCH_STEP_Y;
            boolean hover = in(mx, my, x, y, SWATCH_W, SWATCH_H);
            if (c == chosen) {
                rect(ms, x - 1, y - 1, SWATCH_W + 2, SWATCH_H + 2, 0xFFFFFFFF);
            } else {
                rect(ms, x, y, SWATCH_W, SWATCH_H, hover ? 0xFFB0B4BC : 0xFF0B0C0E);
            }
            rect(ms, x + 1, y + 1, SWATCH_W - 2, SWATCH_H - 2, 0xFF000000 | c.getColorValue());
            rect(ms, x + 1, y + 1, SWATCH_W - 2, 2, 0x38FFFFFF);
            rect(ms, x + 1, y + SWATCH_H - 3, SWATCH_W - 2, 2, 0x38000000);
            if (!baseTab && c == design.base) {
                smallText(ms, "B", x + 2, y + 2, 0.5F, 0xFFFFFFFF); // where the base colour is
            }
        }
    }

    private void drawTab(MatrixStack ms, int x, int w, String label, boolean active, boolean hover) {
        rect(ms, x, TAB_Y, w, TAB_H, 0xFF0B0C0E);
        rect(ms, x + 1, TAB_Y + 1, w - 2, TAB_H - 2, active ? 0xFFC9A227 : hover ? 0xFF4A4E57 : 0xFF33363D);
        smallText(ms, label, x + (w - font.getStringWidth(label) * 0.75F) / 2.0F, TAB_Y + 2, 0.75F,
                active ? 0xFF1B1300 : 0xFFD0D4DA);
    }

    private void drawPatterns(MatrixStack ms, int mx, int my) {
        panel(ms, 102, 66, 145, 97, 0xFF2A2D33);
        text(ms, "2. Select Pattern", 106, 69, 0xFFFFFFFF);
        String hint = selected >= 0 ? "replaces #" + (selected + 1) : "new layer";
        smallText(ms, hint, 243 - font.getStringWidth(hint) * 0.6F, 71, 0.6F, 0xFF9AA0AA);

        // Viewport: fixed size, clipped; only whole rows are ever shown and clickable.
        rect(ms, GRID_X - 1, GRID_Y - 1, GRID_W + 2, GRID_H + 2, 0xFF15171B);
        Minecraft mc = Minecraft.getInstance();
        MainWindow window = mc.getMainWindow();
        double scale = window.getGuiScaleFactor();
        int vx = guiLeft + GRID_X;
        int vy = guiTop + GRID_Y;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) Math.floor(vx * scale), (int) Math.floor(window.getFramebufferHeight() - (vy + GRID_H) * scale),
                (int) Math.ceil(GRID_W * scale), (int) Math.ceil(GRID_H * scale));
        int hovered = gridIndexAt(mx, my);
        BannerPattern selectedPattern = selected >= 0 && selected < design.layers.size() ? design.layers.get(selected).getFirst() : null;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (row + scroll) * COLS + col;
                if (index >= gridPatterns.size()) {
                    break;
                }
                BannerPattern p = gridPatterns.get(index);
                int x = GRID_X + col * STEP_X;
                int y = GRID_Y + row * STEP_Y;
                slotFrame(ms, x, y, CELL_W, CELL_H, false, index == hovered);
                drawFlatBanner(ms, guiLeft + x + 2, guiTop + y + 1, 12, 24, iconBase(dye),
                        Collections.singletonList(Pair.of(p, dye)));
                if (p == selectedPattern) {
                    rect(ms, x, y, CELL_W, 1, 0xFFFFFFFF);
                    rect(ms, x, y + CELL_H - 1, CELL_W, 1, 0xFFFFFFFF);
                    rect(ms, x, y, 1, CELL_H, 0xFFFFFFFF);
                    rect(ms, x + CELL_W - 1, y, 1, CELL_H, 0xFFFFFFFF);
                }
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // Scrollbar
        rect(ms, BAR_X, GRID_Y, BAR_W, GRID_H, 0xFF101114);
        int max = maxScroll();
        int thumb = max == 0 ? GRID_H : Math.max(10, GRID_H * ROWS / rows());
        int thumbY = max == 0 ? 0 : (GRID_H - thumb) * scroll / max;
        boolean barHover = in(mx, my, BAR_X, GRID_Y, BAR_W, GRID_H) || drag == Drag.SCROLLBAR;
        rect(ms, BAR_X, GRID_Y + thumbY, BAR_W, thumb, max == 0 ? 0xFF3A3D45 : barHover ? 0xFFD8DCE2 : 0xFF9A9EA6);
    }

    private void drawItemPanel(MatrixStack ms, int mx, int my) {
        panel(ms, 248, 66, 58, 97, 0xFF2A2D33);
        smallText(ms, "Banner", 251, 70, 0.7F, 0xFFFFFFFF);
        smallText(ms, "Pattern Item", 251, 77, 0.7F, 0xFFFFFFFF);
        int sx = FlagDesignerContainer.PATTERN_SLOT_X - 1;
        int sy = FlagDesignerContainer.PATTERN_SLOT_Y - 1;
        boolean hover = in(mx, my, sx, sy, 18, 18);
        slotFrame(ms, sx, sy, 18, 18, false, hover);
        BannerPattern p = slotPattern();
        // Arrow: slot -> pattern
        rect(ms, 276, 111, 3, 5, 0xFF9AA0AA);
        for (int r = 0; r < 3; r++) {
            rect(ms, 277 - r, 118 - r, 1 + 2 * r, 1, 0xFF9AA0AA);
        }
        boolean resultHover = p != null && in(mx, my, RESULT_X, RESULT_Y, CELL_W, CELL_H);
        slotFrame(ms, RESULT_X, RESULT_Y, CELL_W, CELL_H, false, resultHover);
        if (p != null) {
            drawFlatBanner(ms, guiLeft + RESULT_X + 2, guiTop + RESULT_Y + 1, 12, 24, iconBase(dye),
                    Collections.singletonList(Pair.of(p, dye)));
            smallText(ms, "click to add", 252, 151, 0.6F, 0xFF9AA0AA);
        } else {
            smallText(ms, "?", RESULT_X + 6, RESULT_Y + 9, 1.0F, 0xFF5A5E68);
            smallText(ms, "click the slot", 252, 151, 0.6F, 0xFF9AA0AA);
        }
    }

    private void drawLayers(MatrixStack ms, int mx, int my) {
        panel(ms, 102, 165, 204, 42, 0xFF2A2D33);
        text(ms, "3. Pattern Layers (" + design.layers.size() + "/" + maxLayers + ")", 106, 168, 0xFFFFFFFF);
        boolean baseSelected = target == Target.BASE;
        slotFrame(ms, BASE_SLOT_X, LAYER_Y, LAYER_W, LAYER_H, baseSelected, in(mx, my, BASE_SLOT_X, LAYER_Y, LAYER_W, LAYER_H));
        drawFlatBanner(ms, guiLeft + BASE_SLOT_X + 4, guiTop + LAYER_Y + 2, 10, 18, design.base,
                Collections.<Pair<BannerPattern, DyeColor>>emptyList());
        smallText(ms, "Base", BASE_SLOT_X + 3, LAYER_Y + 20.5F, 0.5F, 0xFFD0D4DA);
        rect(ms, 126, LAYER_Y + 2, 1, LAYER_H - 4, 0xFF4B4F58);
        for (int i = 0; i < maxLayers; i++) {
            int x = LAYER_X + i * LAYER_STEP;
            boolean filled = i < design.layers.size();
            slotFrame(ms, x, LAYER_Y, LAYER_W, LAYER_H, i == selected, filled && in(mx, my, x, LAYER_Y, LAYER_W, LAYER_H));
            if (filled) {
                Pair<BannerPattern, DyeColor> layer = design.layers.get(i);
                drawFlatBanner(ms, guiLeft + x + 4, guiTop + LAYER_Y + 2, 10, 18, iconBase(layer.getSecond()),
                        Collections.singletonList(layer));
            } else {
                rect(ms, x + 8, LAYER_Y + 7, 2, 8, 0xFF4B4F58);
                rect(ms, x + 5, LAYER_Y + 10, 8, 2, 0xFF4B4F58);
            }
            smallText(ms, String.valueOf(i + 1), x + 8, LAYER_Y + 20.5F, 0.5F, 0xFFD0D4DA);
        }
        boolean has = selected >= 0 && selected < design.layers.size();
        drawTool(ms, UP_X, mx, my, has && selected < design.layers.size() - 1, 0);
        drawTool(ms, DOWN_X, mx, my, has && selected > 0, 1);
        drawTool(ms, REMOVE_X, mx, my, has, 2);
    }

    private void drawTool(MatrixStack ms, int x, int mx, int my, boolean enabled, int icon) {
        boolean hover = enabled && in(mx, my, x, TOOL_Y, TOOL_S, TOOL_S);
        rect(ms, x, TOOL_Y, TOOL_S, TOOL_S, 0xFF0B0C0E);
        fillGradient(ms, guiLeft + x + 1, guiTop + TOOL_Y + 1, guiLeft + x + TOOL_S - 1, guiTop + TOOL_Y + TOOL_S - 1,
                enabled ? (hover ? 0xFF9A9EA6 : 0xFF7E828A) : 0xFF3E4146, enabled ? (hover ? 0xFF656970 : 0xFF54575D) : 0xFF34363A);
        int color = enabled ? 0xFFFFFFFF : 0xFF6E7176;
        if (icon == 0) {
            arrowIcon(ms, x, TOOL_Y, true, color);
        } else if (icon == 1) {
            arrowIcon(ms, x, TOOL_Y, false, color);
        } else {
            binIcon(ms, x, TOOL_Y, color);
        }
    }

    private void drawNotice(MatrixStack ms) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : notice.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (font.getStringWidth(next) > 180 && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        while (lines.size() > 6) {
            lines.remove(lines.size() - 1);
        }
        int h = lines.size() * 10 + 20;
        int x0 = guiLeft + 108;
        int y0 = guiTop + 66 + Math.max(2, (97 - h) / 2);
        ms.push();
        ms.translate(0.0D, 0.0D, 350.0D);
        fill(ms, x0 - 1, y0 - 1, x0 + 193, y0 + h + 1, 0xFFB03A3A);
        fill(ms, x0, y0, x0 + 192, y0 + h, 0xF2241214);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            font.drawStringWithShadow(ms, l, x0 + 96 - font.getStringWidth(l) / 2.0F, y0 + 5 + i * 10, 0xFFFF7A7A);
        }
        String dismiss = "(click to dismiss)";
        font.drawString(ms, dismiss, x0 + 96 - font.getStringWidth(dismiss) / 2.0F, y0 + h - 12, 0xFF8E949E);
        ms.pop();
    }

    private void drawPopup(MatrixStack ms, int mouseX, int mouseY) {
        List<Slot> candidates = patternItemSlots();
        ms.push();
        ms.translate(0.0D, 0.0D, 300.0D);
        fill(ms, guiLeft + POPUP_X - 1, guiTop + POPUP_Y - 1, guiLeft + POPUP_X + POPUP_W + 1, guiTop + POPUP_Y + POPUP_H + 1, 0xFFC9A227);
        fill(ms, guiLeft + POPUP_X, guiTop + POPUP_Y, guiLeft + POPUP_X + POPUP_W, guiTop + POPUP_Y + POPUP_H, 0xF51B1D22);
        font.drawStringWithShadow(ms, "Choose a pattern item", guiLeft + POPUP_X + 6, guiTop + POPUP_Y + 4, 0xFFFFFFFF);
        int hovered = popupIndexAt(mouseX, mouseY, candidates.size());
        for (int i = 0; i < candidates.size() && i < 28; i++) {
            int x = guiLeft + POPUP_X + 6 + (i % POPUP_COLS) * 18;
            int y = guiTop + POPUP_Y + 16 + (i / POPUP_COLS) * 18;
            fill(ms, x, y, x + 18, y + 18, i == hovered ? 0xFF5A5E68 : 0xFF2A2D33);
            fill(ms, x + 1, y + 1, x + 17, y + 17, i == hovered ? 0xFF3A3E47 : 0xFF202227);
        }
        ms.pop();
        float oldZ = itemRenderer.zLevel;
        itemRenderer.zLevel = 300.0F;
        for (int i = 0; i < candidates.size() && i < 28; i++) {
            itemRenderer.renderItemIntoGUI(candidates.get(i).getStack(), guiLeft + POPUP_X + 7 + (i % POPUP_COLS) * 18,
                    guiTop + POPUP_Y + 17 + (i / POPUP_COLS) * 18);
        }
        itemRenderer.zLevel = oldZ;
        if (hovered >= 0) {
            renderTooltip(ms, candidates.get(hovered).getStack(), mouseX, mouseY);
        }
    }

    private void drawTooltips(MatrixStack ms, int mx, int my) {
        if (drag != Drag.NONE) {
            return;
        }
        int index = gridIndexAt(mx, my);
        if (notice == null && index >= 0) {
            renderTooltip(ms, patternName(gridPatterns.get(index), dye), mx, my);
            return;
        }
        int swatch = swatchAt(mx, my);
        if (notice == null && swatch >= 0) {
            renderTooltip(ms, new TranslationTextComponent("color.minecraft." + DyeColor.byId(swatch).getTranslationKey()), mx, my);
            return;
        }
        BannerPattern p = slotPattern();
        if (notice == null && p != null && in(mx, my, RESULT_X, RESULT_Y, CELL_W, CELL_H)) {
            renderTooltip(ms, patternName(p, dye), mx, my);
            return;
        }
        if (notice == null && p == null && in(mx, my, FlagDesignerContainer.PATTERN_SLOT_X - 1, FlagDesignerContainer.PATTERN_SLOT_Y - 1, 18, 18)) {
            renderTooltip(ms, new StringTextComponent("Click to place a banner pattern item from your inventory"), mx, my);
            return;
        }
        if (in(mx, my, BASE_SLOT_X, LAYER_Y, LAYER_W, LAYER_H)) {
            renderTooltip(ms, new StringTextComponent("Base: ").appendSibling(
                    new TranslationTextComponent("color.minecraft." + design.base.getTranslationKey())), mx, my);
            return;
        }
        int layer = layerAt(mx, my);
        if (layer >= 0 && layer < design.layers.size()) {
            Pair<BannerPattern, DyeColor> l = design.layers.get(layer);
            renderTooltip(ms, new StringTextComponent((layer + 1) + ". ").appendSibling(patternName(l.getFirst(), l.getSecond())), mx, my);
            return;
        }
        if (in(mx, my, UP_X, TOOL_Y, TOOL_S, TOOL_S)) {
            renderTooltip(ms, new StringTextComponent("Move Up (drawn over the next layer)"), mx, my);
        } else if (in(mx, my, DOWN_X, TOOL_Y, TOOL_S, TOOL_S)) {
            renderTooltip(ms, new StringTextComponent("Move Down (drawn under the previous layer)"), mx, my);
        } else if (in(mx, my, REMOVE_X, TOOL_Y, TOOL_S, TOOL_S)) {
            renderTooltip(ms, new StringTextComponent("Remove Layer"), mx, my);
        } else if (in(mx, my, RESET_X, RESET_Y, RESET_W, RESET_H)) {
            renderTooltip(ms, new StringTextComponent("Reset view (or double-click / R)"), mx, my);
        } else if (in(mx, my, TAB_BASE_X, TAB_Y, TAB_W, TAB_H)) {
            renderTooltip(ms, new StringTextComponent("Colours change the banner's base"), mx, my);
        } else if (in(mx, my, TAB_PATTERN_X, TAB_Y, TAB_PATTERN_W, TAB_H)) {
            renderTooltip(ms, new StringTextComponent("Colours set the pattern dye (and recolour the selected layer)"), mx, my);
        }
    }
}
