package net.flanwarfare.flags.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.flanwarfare.flags.FlagBlock;
import net.flanwarfare.flags.FlagTileEntity;
import net.flanwarfare.flags.FlagsMod;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.client.renderer.tileentity.BannerTileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix3f;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3f;

/**
 * Draws the whole claim flag from the base block. All sizes are in pixels (16 = one block),
 * measured from the base block's corner, for a flag facing SOUTH (banner front towards +Z);
 * other facings rotate the complete assembly around the block centre.
 *
 * <pre>
 *   y 0    .. 2     lower pedestal tier   16 x 16       (1 x 1 x 1/8 block)
 *   y 2    .. 6     upper pedestal tier    8 x 8        (1/2 x 1/2 x 1/4)
 *   y 6    .. 94    wooden pole            2 x 2        (1/8 x 1/8), runs behind the banner
 *   y 58   .. 90    banner fabric         16 x 32       (1 x 2 blocks), 0.8 px thick, raised
 *   y 6    .. 58    exposed shaft under the raised banner: 52 px = 3.25 blocks (1.6 x fabric)
 *   y 90   .. 92.5  crossbar              20 wide       (1.25 blocks) with 3 x 3.5 end caps
 *   y 88   .. 90    four attachment tabs over the fabric's top edge
 *   y 94   .. 99    gold finial: 4 x 3 x 4 with a 2 x 2 x 2 cube on top
 *   total height 99 px = 6.19 blocks
 * </pre>
 * The fabric is the vanilla banner model and pattern renderer, so every vanilla design shows
 * exactly as on a banner, both sides are drawn, and it sits 0.3 px in front of the pole (no
 * z-fighting). During a capture the fabric slides down the pole (Raise 1 -> 0).
 */
public final class FlagRenderer extends TileEntityRenderer<FlagTileEntity> {
    private static final ResourceLocation PARTS = new ResourceLocation(FlagsMod.MOD_ID, "textures/entity/flag_parts.png");
    private static final float TEX = 64.0F;

    // Texture tiles (16x16 each) in flag_parts.png.
    private static final int[] WOOD = {0, 0};
    private static final int[] DARK = {16, 0};
    private static final int[] STONE = {32, 0};
    private static final int[] GOLD = {48, 0};
    private static final int[] STONE_TOP = {0, 16};
    private static final int[] GOLD_TOP = {16, 16};

    /** Raised fabric top and the lowest the top can go (fabric bottom 2 px above the pedestal). */
    private static final float FABRIC_TOP = 90.0F;
    private static final float FABRIC_TOP_LOWEST = 40.0F;

    private final ModelRenderer fabric;

    public FlagRenderer(TileEntityRendererDispatcher dispatcher) {
        super(dispatcher);
        // Same geometry as the vanilla banner flag (20 x 40 x 1 model pixels, 64 x 64 texture).
        fabric = new ModelRenderer(64, 64, 0, 0);
        fabric.addBox(-10.0F, 0.0F, -2.0F, 20.0F, 40.0F, 1.0F, 0.0F);
    }

    @Override
    public boolean isGlobalRenderer(FlagTileEntity te) {
        return true; // taller than one chunk section: render whenever its bounding box is in view
    }

    @Override
    public void render(FlagTileEntity te, float partialTicks, MatrixStack stack, IRenderTypeBuffer buffers,
                       int light, int overlay) {
        BlockState state = te.getBlockState();
        Direction facing = state.getBlock() instanceof FlagBlock ? state.get(FlagBlock.FACING) : Direction.SOUTH;

        stack.push();
        stack.translate(0.5D, 0.0D, 0.5D);
        stack.rotate(Vector3f.YP.rotationDegrees(-facing.getHorizontalAngle()));
        stack.translate(-0.5D, 0.0D, -0.5D);

        IVertexBuilder vb = buffers.getBuffer(RenderType.getEntityCutoutNoCull(PARTS));
        Matrix4f pose = stack.getLast().getMatrix();
        Matrix3f normal = stack.getLast().getNormal();

        // Pedestal
        box(vb, pose, normal, light, overlay, 0, 0, 0, 16, 2, 16, STONE, STONE_TOP);
        box(vb, pose, normal, light, overlay, 4, 2, 4, 12, 6, 12, STONE, STONE_TOP);
        // Pole
        box(vb, pose, normal, light, overlay, 7, 6, 7, 9, 94, 9, WOOD, WOOD);
        // Crossbar, end caps and attachment tabs (dark wood)
        box(vb, pose, normal, light, overlay, -2, 90, 7, 18, 92.5F, 10.6F, DARK, DARK);
        box(vb, pose, normal, light, overlay, -2.5F, 89.5F, 6.5F, 0.5F, 93, 11, DARK, DARK);
        box(vb, pose, normal, light, overlay, 15.5F, 89.5F, 6.5F, 18.5F, 93, 11, DARK, DARK);
        // Gold finial
        box(vb, pose, normal, light, overlay, 6, 94, 6, 10, 97, 10, GOLD, GOLD_TOP);
        box(vb, pose, normal, light, overlay, 7, 97, 7, 9, 99, 9, GOLD, GOLD_TOP);

        float raise = te.animatedRaise();
        float top = FABRIC_TOP_LOWEST + (FABRIC_TOP - FABRIC_TOP_LOWEST) * raise;
        if (raise >= 0.999F) {
            // Tabs only connect when the banner hangs from the crossbar.
            for (int i = 0; i < 4; i++) {
                float x = 1.25F + i * 4.0F;
                box(vb, pose, normal, light, overlay, x, 88, 10.1F, x + 1.5F, 90, 10.6F, DARK, DARK);
            }
        }

        // Fabric: vanilla banner flag model scaled 0.8 -> 16 x 32 px, back face at z = 9.3 px.
        stack.push();
        stack.translate(8.0D / 16.0D, (top - 25.6D) / 16.0D, 8.5D / 16.0D);
        stack.scale(0.8F, -0.8F, -0.8F);
        fabric.rotationPointY = -32.0F;
        fabric.rotateAngleX = 0.0F;
        BannerTileEntityRenderer.renderPatterns(stack, buffers, light, overlay, fabric, ModelBakery.LOCATION_BANNER_BASE,
                true, te.patternLayers());
        stack.pop();

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
