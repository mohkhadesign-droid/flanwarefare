package net.flanwarfare.designer.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.flanwarfare.designer.DesignerMod;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.client.renderer.tileentity.BannerTileEntityRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix3f;
import net.minecraft.util.math.vector.Matrix4f;

/**
 * Draws the claim flag model for the designer's 3D preview: upper pole, crossbar, caps, finial,
 * cloth tabs and the banner (vanilla's banner pattern renderer). This is a copy of the Flan
 * Warfare Flags world renderer's geometry, so the Designer mod works on its own, with or without
 * the Flags mod.
 */
final class PreviewRenderer {
    private static final ResourceLocation PARTS = new ResourceLocation(DesignerMod.MOD_ID, "textures/entity/flag_parts.png");
    private static final float TEX = 64.0F;
    private static final int[] WOOD = {0, 0};
    private static final int[] DARK = {16, 0};
    private static final int[] STONE = {32, 0};
    private static final int[] GOLD = {48, 0};
    private static final int[] STONE_TOP = {0, 16};
    private static final int[] GOLD_TOP = {16, 16};

    private PreviewRenderer() {
    }

    /** The vanilla banner flag geometry (20 x 40 x 1 model px, 64 x 64 texture). */
    static ModelRenderer newFabric() {
        ModelRenderer m = new ModelRenderer(64, 64, 0, 0);
        m.addBox(-10.0F, 0.0F, -2.0F, 20.0F, 40.0F, 1.0F, 0.0F);
        return m;
    }

    /**
     * Pedestal (if {@code fromY} is 0), pole from {@code fromY} px up, crossbar, caps, finial and,
     * with {@code tabs}, the cloth tabs. Shared by the world renderer and the designer preview.
     */
    static void drawStructure(MatrixStack stack, IRenderTypeBuffer buffers, int light, int overlay, float fromY, boolean tabs) {
        IVertexBuilder vb = buffers.getBuffer(RenderType.getEntityCutoutNoCull(PARTS));
        Matrix4f pose = stack.getLast().getMatrix();
        Matrix3f normal = stack.getLast().getNormal();
        if (fromY <= 0.0F) {
            box(vb, pose, normal, light, overlay, 0, 0, 0, 16, 2, 16, STONE, STONE_TOP);
            box(vb, pose, normal, light, overlay, 4, 2, 4, 12, 6, 12, STONE, STONE_TOP);
        }
        box(vb, pose, normal, light, overlay, 7, Math.max(6.0F, fromY), 7, 9, 94, 9, WOOD, WOOD);
        box(vb, pose, normal, light, overlay, -2, 90, 7, 18, 92.5F, 10.6F, DARK, DARK);
        box(vb, pose, normal, light, overlay, -2.5F, 89.5F, 6.5F, 0.5F, 93, 11, DARK, DARK);
        box(vb, pose, normal, light, overlay, 15.5F, 89.5F, 6.5F, 18.5F, 93, 11, DARK, DARK);
        box(vb, pose, normal, light, overlay, 6, 94, 6, 10, 97, 10, GOLD, GOLD_TOP);
        box(vb, pose, normal, light, overlay, 7, 97, 7, 9, 99, 9, GOLD, GOLD_TOP);
        if (tabs) {
            for (int i = 0; i < 4; i++) {
                float x = 1.25F + i * 4.0F;
                box(vb, pose, normal, light, overlay, x, 88, 10.1F, x + 1.5F, 90, 10.6F, DARK, DARK);
            }
        }
    }

    /** The banner cloth (vanilla pattern renderer), its top edge at {@code topPx}. */
    static void drawFabric(MatrixStack stack, IRenderTypeBuffer buffers, int light, int overlay, ModelRenderer fabric,
                           float topPx, java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.tileentity.BannerPattern,
                           net.minecraft.item.DyeColor>> layers) {
        stack.push();
        stack.translate(8.0D / 16.0D, (topPx - 25.6D) / 16.0D, 8.5D / 16.0D);
        stack.scale(0.8F, -0.8F, -0.8F);
        fabric.rotationPointY = -32.0F;
        fabric.rotateAngleX = 0.0F;
        BannerTileEntityRenderer.func_230180_a_(stack, buffers, light, overlay, fabric, ModelBakery.LOCATION_BANNER_BASE,
                true, layers);
        stack.pop();
    }

    // ------------------------------------------------------------------ cuboids

    /**
     * Draws a cuboid (pixel coordinates). Faces longer than 16 px are split into 16 px pieces,
     * so the 16 x 16 texture tiles keep one texel per pixel everywhere (no stretching).
     */
    private static void box(IVertexBuilder vb, Matrix4f pose, Matrix3f normal, int light, int overlay,
                            float x0, float y0, float z0, float x1, float y1, float z1, int[] side, int[] top) {
        // up / down
        face(vb, pose, normal, light, overlay, top, 0, 1, 0, x0, z0, x1, z1, y1, 'y');
        face(vb, pose, normal, light, overlay, top, 0, -1, 0, x0, z0, x1, z1, y0, 'y');
        // north / south (z faces)
        face(vb, pose, normal, light, overlay, side, 0, 0, -1, x0, y0, x1, y1, z0, 'z');
        face(vb, pose, normal, light, overlay, side, 0, 0, 1, x0, y0, x1, y1, z1, 'z');
        // west / east (x faces)
        face(vb, pose, normal, light, overlay, side, -1, 0, 0, z0, y0, z1, y1, x0, 'x');
        face(vb, pose, normal, light, overlay, side, 1, 0, 0, z0, y0, z1, y1, x1, 'x');
    }

    /** One face, split into tiles of at most 16 x 16 px. (a, b) are the face's in-plane axes. */
    private static void face(IVertexBuilder vb, Matrix4f pose, Matrix3f normal, int light, int overlay, int[] tile,
                             float nx, float ny, float nz, float a0, float b0, float a1, float b1, float c, char axis) {
        for (float a = a0; a < a1 - 1e-4F; a += 16.0F) {
            float ae = Math.min(a1, a + 16.0F);
            for (float b = b0; b < b1 - 1e-4F; b += 16.0F) {
                float be = Math.min(b1, b + 16.0F);
                float ou = Math.min(mod16(a), 16.0F - (ae - a));
                float ov = Math.min(mod16(b), 16.0F - (be - b));
                float u0 = (tile[0] + ou) / TEX;
                float u1 = (tile[0] + ou + (ae - a)) / TEX;
                float v0 = (tile[1] + ov) / TEX;
                float v1 = (tile[1] + ov + (be - b)) / TEX;
                // Texture v runs downwards: map the high b edge to v0 for vertical faces.
                if (axis != 'y') {
                    float t = v0;
                    v0 = v1;
                    v1 = t;
                }
                float[][] corners = {{a, b}, {ae, b}, {ae, be}, {a, be}};
                float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                for (int i = 0; i < 4; i++) {
                    float x;
                    float y;
                    float z;
                    if (axis == 'y') {
                        x = corners[i][0];
                        y = c;
                        z = corners[i][1];
                    } else if (axis == 'z') {
                        x = corners[i][0];
                        y = corners[i][1];
                        z = c;
                    } else {
                        x = c;
                        y = corners[i][1];
                        z = corners[i][0];
                    }
                    vb.pos(pose, x / 16.0F, y / 16.0F, z / 16.0F)
                            .color(255, 255, 255, 255)
                            .tex(uv[i][0], uv[i][1])
                            .overlay(overlay)
                            .lightmap(light)
                            .normal(normal, nx, ny, nz)
                            .endVertex();
                }
            }
        }
    }

    /** Offset inside a 16 px tile so a piece starting mid-tile keeps continuous texels. */
    private static float mod16(float v) {
        float m = v % 16.0F;
        return m < 0 ? m + 16.0F : m;
    }
}
