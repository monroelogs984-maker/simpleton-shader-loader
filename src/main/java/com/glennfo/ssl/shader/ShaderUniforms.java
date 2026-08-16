package com.glennfo.ssl.shader;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;

import java.nio.FloatBuffer;

/**
 * Captures per-frame GL state and populates standard OptiFine uniforms
 * into any shader program that requests them.
 *
 * Capture order matters:
 *   1. captureMatrices() — called after setupCameraTransform so matrices are clean
 *   2. populate()        — called once per program activation
 */
public class ShaderUniforms {

    private static final FloatBuffer MAT_BUF = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer UNI_BUF = BufferUtils.createFloatBuffer(16);

    // Captured camera matrices (column-major, as GL stores them)
    static final float[] modelView        = new float[16];
    static final float[] modelViewInverse = new float[16];
    static final float[] projection        = new float[16];
    static final float[] projectionInverse = new float[16];

    // Shadow matrices — filled by ShadowRenderer when the shadow pass runs
    static final float[] shadowModelView        = new float[16];
    static final float[] shadowProjection        = new float[16];
    static final float[] shadowModelViewInverse  = new float[16];
    static final float[] shadowProjectionInverse = new float[16];

    // Previous-frame gbuffer matrices for temporal/motion effects (identity on first frame)
    static final float[] previousModelView  = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    static final float[] previousProjection = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    private static int   frameCounter     = 0;
    private static float frameTime        = 0f;
    private static float frameTimeCounter = 0f; // cumulative seconds
    private static long  lastFrameNano    = System.nanoTime();

    // Frame-interpolated camera position, and last frame's value for TAA reprojection.
    // Raw entity pos updates at tick rate (20/s), not frame rate — using it directly
    // makes cameraPosition jump between frames, and previousCameraPosition == current
    // breaks temporal reprojection entirely (CU's TAA jitter then shows up raw).
    private static double camX, camY, camZ;
    private static double prevCamX, prevCamY, prevCamZ;

    // Exponentially smoothed eyeBrightness — packs use it for gradual exposure /
    // sky-light adaptation when moving between caves and daylight. Never setting
    // it leaves shaders thinking the eye is permanently in pitch black.
    private static float eyeBrightSmoothX = 240f, eyeBrightSmoothY = 240f;

    // Custom-uniform smoothing state (native equivalents of CU's shaders.properties
    // expressions — see setCustomUniforms). A full expression interpreter is
    // future work; these cover Complementary's lighting-critical set.
    private static float isEyeInCaveS = 0f;
    private static float eyeBrightM2S = 1f;
    private static float rainFactorS = 0f;
    private static float frameTimeSmoothS = 1f / 60f;
    private static float inDryS = 0f, inRainyS = 1f, inSnowyS = 0f;

    private static float smoothTo(float current, float target, float rate) {
        return current + (target - current) * (1f - (float) Math.exp(-frameTime * rate));
    }

    /** Called right after EntityRenderer.setupCameraTransform — matrices are valid here. */
    public static void captureMatrices() {
        Minecraft mcInst = Minecraft.getMinecraft();
        net.minecraft.entity.Entity view = mcInst.getRenderViewEntity();
        if (view != null) {
            prevCamX = camX; prevCamY = camY; prevCamZ = camZ;
            float pt = mcInst.getRenderPartialTicks();
            camX = view.prevPosX + (view.posX - view.prevPosX) * pt;
            camY = view.prevPosY + (view.posY - view.prevPosY) * pt;
            camZ = view.prevPosZ + (view.posZ - view.prevPosZ) * pt;
        }
        // Save previous-frame matrices before overwriting
        System.arraycopy(modelView,  0, previousModelView,  0, 16);
        System.arraycopy(projection, 0, previousProjection, 0, 16);

        MAT_BUF.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT_BUF);
        MAT_BUF.get(modelView); MAT_BUF.flip();

        MAT_BUF.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT_BUF);
        MAT_BUF.get(projection); MAT_BUF.flip();

        invert(modelView, modelViewInverse);
        invert(projection, projectionInverse);

        long now = System.nanoTime();
        frameTime = (now - lastFrameNano) / 1_000_000_000f;
        lastFrameNano = now;
        frameTimeCounter += frameTime;
        frameCounter++;

        // Smooth eyeBrightness toward the current value (~0.3s time constant)
        if (mcInst.player != null) {
            int combined = mcInst.player.getBrightnessForRender();
            float targetX = combined & 0xFFFF, targetY = (combined >> 16) & 0xFFFF;
            float a = 1f - (float) Math.exp(-frameTime * 3f);
            eyeBrightSmoothX += (targetX - eyeBrightSmoothX) * a;
            eyeBrightSmoothY += (targetY - eyeBrightSmoothY) * a;
        }

        updateCustomUniformState(mcInst);
    }

    /** Per-frame smoothing for the native custom-uniform equivalents. */
    private static void updateCustomUniformState(Minecraft mc) {
        if (mc.player == null || mc.world == null) return;

        float eyeY = (float) (mc.player.posY + mc.player.getEyeHeight());
        int combined = mc.player.getBrightnessForRender();
        float eyeSky = ((combined >> 16) & 0xFFFF) / 240f;

        // isEyeInCave: fades in when underground with low skylight, 0 in water
        boolean inWater = mc.player.isInsideOfMaterial(Material.WATER);
        float caveTarget = inWater ? 0f : 1f - (eyeY < 5.0f ? eyeSky : 1f);
        isEyeInCaveS = smoothTo(isEyeInCaveS, caveTarget, 2f);

        // eyeBrightnessM2: "is the eye in full skylight", fast smooth
        eyeBrightM2S = smoothTo(eyeBrightM2S, eyeSky > 239f / 240f ? 1f : 0f, 4f);

        rainFactorS = smoothTo(rainFactorS, mc.world.getRainStrength(0f), 3f);
        frameTimeSmoothS = smoothTo(frameTimeSmoothS, frameTime, 2f);

        // Biome precipitation type at the camera
        net.minecraft.world.biome.Biome biome = mc.world.getBiome(
            new net.minecraft.util.math.BlockPos(camX, camY, camZ));
        boolean snowy = biome.getEnableSnow();
        boolean rainy = !snowy && biome.canRain();
        boolean dry   = !snowy && !rainy;
        inDryS   = smoothTo(inDryS,   dry   ? 1f : 0f, 1.5f);
        inRainyS = smoothTo(inRainyS, rainy ? 1f : 0f, 1.5f);
        inSnowyS = smoothTo(inSnowyS, snowy ? 1f : 0f, 1.5f);
    }

    /**
     * Sets every standard OptiFine uniform on the given program that exists in its source.
     * Missing uniforms are silently ignored (location == -1).
     */
    public static void populate(ShaderProgram prog, Minecraft mc) {
        // --- Screen ---
        float w = mc.displayWidth, h = mc.displayHeight;
        setFloat(prog, "viewWidth",   w);
        setFloat(prog, "viewHeight",  h);
        setFloat(prog, "aspectRatio", w / h);
        setFloat(prog, "near",  0.05f);
        setFloat(prog, "far",   mc.gameSettings.renderDistanceChunks * 16f);

        // --- Time / weather ---
        setInt  (prog, "frameCounter", frameCounter);
        setFloat(prog, "frameTime",    frameTime);

        // OptiFine's sunAngle convention: 0 = sunrise, 0.25 = noon, 0.5 = sunset.
        // MC's celestialAngle has 0 = noon — passing it raw makes packs compute
        // night factors at midday (CU: noonFactor=0, nightFactor>0 → dim gray
        // "night" ground lighting all day). Positions below keep celestialAngle,
        // which matches the sun's actual render rotation.
        float celestialAngle = 0f;
        float rainStrength = 0f;
        if (mc.world != null) {
            celestialAngle = mc.world.getCelestialAngle(0f);
            rainStrength   = mc.world.getRainStrength(0f);
        }
        float sunAngle = (celestialAngle + 0.25f) % 1.0f;
        setFloat(prog, "sunAngle",      sunAngle);
        setFloat(prog, "rainStrength",  rainStrength);
        setFloat(prog, "weatherStrength", rainStrength);

        // --- Camera position (frame-interpolated; captured in captureMatrices) ---
        setVec3(prog, "cameraPosition",         (float) camX,     (float) camY,     (float) camZ);
        setVec3(prog, "previousCameraPosition", (float) prevCamX, (float) prevCamY, (float) prevCamZ);

        // --- Sun / moon / shadow light positions (view space) ---
        // These MUST be in view (eye) space. CU and all OptiFine-format packs compute
        // directional lighting as dot(normal_viewspace, normalize(sunPosition)), so both
        // operands must be in the same space. World-space sun dirs give wrong dot products
        // from every camera angle and cause the "everything is a dark silhouette" effect.
        // Transform world-space direction through the rotation part of the view matrix
        // (upper-left 3×3 of the column-major modelView; translation row is ignored for dirs).
        float sa = celestialAngle * 2f * (float) Math.PI;
        float sx = -MathHelper.sin(sa);
        float sy =  MathHelper.cos(sa);
        float[] sunView  = dirToViewSpace(modelView,  sx * 100f,  sy * 100f, 0f);
        float[] moonView = dirToViewSpace(modelView, -sx * 100f, -sy * 100f, 0f);
        float[] upView   = dirToViewSpace(modelView, 0f, 100f, 0f);
        setVec3(prog, "sunPosition",  sunView[0],  sunView[1],  sunView[2]);
        setVec3(prog, "moonPosition", moonView[0], moonView[1], moonView[2]);
        setVec3(prog, "upPosition",   upView[0],   upView[1],   upView[2]);
        // Shadow light = sun during day, moon during night
        boolean isNight = sunAngle > 0.5f;
        float[] shadowLightView = isNight ? moonView : sunView;
        setVec3(prog, "shadowLightPosition", shadowLightView[0], shadowLightView[1], shadowLightView[2]);

        // --- Gbuffer matrices (and their legacy unprefixed aliases) ---
        setMat4(prog, "gbufferModelView",           modelView);
        setMat4(prog, "gbufferModelViewInverse",    modelViewInverse);
        setMat4(prog, "gbufferProjection",          projection);
        setMat4(prog, "gbufferProjectionInverse",   projectionInverse);
        setMat4(prog, "modelViewMatrix",            modelView);
        setMat4(prog, "modelViewMatrixInverse",     modelViewInverse);
        setMat4(prog, "projectionMatrix",           projection);
        setMat4(prog, "projectionMatrixInverse",    projectionInverse);

        // --- Shadow matrices ---
        setMat4(prog, "shadowModelView",            shadowModelView);
        setMat4(prog, "shadowProjection",           shadowProjection);
        setMat4(prog, "shadowModelViewInverse",     shadowModelViewInverse);
        setMat4(prog, "shadowProjectionInverse",    shadowProjectionInverse);

        // --- Texture unit assignments (must match MultiAttachmentFbo.bindTexturesToUnits) ---
        // colortex0-7 → units 0-7
        setInt(prog, "colortex0", 0);
        setInt(prog, "colortex1", 1);
        setInt(prog, "colortex2", 2);
        setInt(prog, "colortex3", 3);
        setInt(prog, "colortex4", 4);
        setInt(prog, "colortex5", 5);
        setInt(prog, "colortex6", 6);
        setInt(prog, "colortex7", 7);
        // depth and shadow → units 8-12
        setInt(prog, "depthtex0",    MultiAttachmentFbo.UNIT_DEPTHTEX0);
        setInt(prog, "depthtex1",    MultiAttachmentFbo.UNIT_DEPTHTEX1);
        setShadowSampler(prog, "shadowtex0");
        setShadowSampler(prog, "shadowtex1");
        setInt(prog, "shadowcolor0", MultiAttachmentFbo.UNIT_SHADOWCOLOR);
        // MC-bound texture units for gbuffer shaders (block atlas, lightmap, etc.)
        setInt(prog, "tex",      0);  // MC block/entity atlas (already bound to unit 0 by MC)
        setInt(prog, "lightmap", 1);  // MC lightmap (unit 1 via GL_TEXTURE1)
        setInt(prog, "normals",  2);  // normal map (not provided — zero texture)
        setInt(prog, "specular", 3);  // specular map (not provided)
        // gaux1-4 are aliases for colortex4-7
        setInt(prog, "gaux1", 4);
        setInt(prog, "gaux2", 5);
        setInt(prog, "gaux3", 6);
        setInt(prog, "gaux4", 7);
        // Legacy OptiFine sampler names (pre-colortexN packs like Sildur's).
        // Unset sampler uniforms default to unit 0, silently reading the wrong
        // texture — "composite" reading the color buffer instead of colortex3
        // is invisible at compile time and garbage at runtime.
        setInt(prog, "texture",   0);  // block atlas (gbuffers)
        setInt(prog, "gcolor",    0);
        setInt(prog, "gdepth",    1);
        setInt(prog, "gnormal",   2);
        setInt(prog, "composite", 3);
        setInt(prog, "gdepthtex", MultiAttachmentFbo.UNIT_DEPTHTEX0);
        // depthtex2 = depth without hand; nearest equivalent we have is the
        // pre-translucent snapshot (depthtex1's unit)
        setInt(prog, "depthtex2", MultiAttachmentFbo.UNIT_DEPTHTEX1);
        setShadowSampler(prog, "shadow");
        setShadowSampler(prog, "watershadow");
        // noise texture at unit 15
        setInt(prog, "noisetex", MultiAttachmentFbo.UNIT_NOISETEX);

        // --- World/player state ---
        setFloat(prog, "frameTimeCounter", frameTimeCounter);
        if (mc.world != null) {
            setInt  (prog, "worldTime",  (int)(mc.world.getWorldTime() % 24000));
            setInt  (prog, "worldDay",   (int)(mc.world.getTotalWorldTime() / 24000));
            setInt  (prog, "moonPhase",  mc.world.provider.getMoonPhase(mc.world.getWorldTime()));
            setFloat(prog, "wetness",    mc.world.getRainStrength(0f));
            Vec3d sky = mc.world.getSkyColor(mc.getRenderViewEntity(), 0f);
            if (sky != null) setVec3(prog, "skyColor", (float)sky.x, (float)sky.y, (float)sky.z);
        }
        setFloat(prog, "screenBrightness", mc.gameSettings.gammaSetting);
        if (mc.player != null) {
            float eyeY = (float)(mc.player.posY + mc.player.getEyeHeight());
            setFloat(prog, "eyeAltitude", eyeY);
            setVec3(prog, "eyePosition", (float)mc.player.posX, eyeY, (float)mc.player.posZ);
            boolean inWater = mc.player.isInsideOfMaterial(Material.WATER);
            boolean inLava  = mc.player.isInsideOfMaterial(Material.LAVA);
            setInt(prog, "isEyeInWater", inWater ? 1 : (inLava ? 2 : 0));
            int combined = mc.player.getBrightnessForRender();
            setIVec2(prog, "eyeBrightness", combined & 0xFFFF, (combined >> 16) & 0xFFFF);
            setIVec2(prog, "eyeBrightnessSmooth", Math.round(eyeBrightSmoothX), Math.round(eyeBrightSmoothY));
        }
        // Fog color from GL state — LWJGL 2 glGetFloat requires remaining() >= 16 regardless of pname
        {
            MAT_BUF.clear();
            GL11.glGetFloat(GL11.GL_FOG_COLOR, MAT_BUF);
            setVec3(prog, "fogColor", MAT_BUF.get(0), MAT_BUF.get(1), MAT_BUF.get(2));
        }

        // --- Temporal / previous-frame matrices ---
        setMat4(prog, "gbufferPreviousModelView",  previousModelView);
        setMat4(prog, "gbufferPreviousProjection", previousProjection);

        setCustomUniforms(prog, mc);
    }

    /**
     * Native equivalents of Complementary's shaders.properties custom uniforms.
     * All lighting-critical: eyeBrightnessM scales ambient/sky light — a shader
     * that declares it but never receives it sees permanent cave darkness.
     * Only set where declared (location -1 skips), so other packs are unaffected.
     */
    private static void setCustomUniforms(ShaderProgram prog, Minecraft mc) {
        setFloat(prog, "framemod2",   frameCounter % 2);
        setFloat(prog, "framemod4",   frameCounter % 4);
        setFloat(prog, "framemod8",   frameCounter % 8);
        setFloat(prog, "framemod600", frameCounter % 600);
        setFloat(prog, "eyeBrightnessM",  eyeBrightSmoothY / 240f);
        setFloat(prog, "eyeBrightnessM2", eyeBrightM2S);
        setFloat(prog, "isEyeInCave",     isEyeInCaveS);
        setFloat(prog, "rainFactor",      rainFactorS);
        setFloat(prog, "frameTimeSmooth", frameTimeSmoothS);
        setFloat(prog, "inDry",   inDryS);
        setFloat(prog, "inRainy", inRainyS);
        setFloat(prog, "inSnowy", inSnowyS);
        // "starter" ramps 0→1 once the player has moved (world warm-up fade)
        setFloat(prog, "starter", 1f);
        // 1.12.2 has a single nether biome — map dimension -1 to nether wastes
        boolean nether = mc.world != null && mc.world.provider.getDimension() == -1;
        setFloat(prog, "inNetherWastes",  nether ? 1f : 0f);
        setFloat(prog, "inCrimsonForest", 0f);
        setFloat(prog, "inWarpedForest",  0f);
        setFloat(prog, "inBasaltDeltas",  0f);
        setFloat(prog, "inSoulValley",    0f);
        setFloat(prog, "inPaleGarden",    0f);
        // Standard blindness + CU's maxBlindnessDarkness (no darkness effect in 1.12)
        float blind = (mc.player != null && mc.player.isPotionActive(net.minecraft.init.MobEffects.BLINDNESS)) ? 1f : 0f;
        setFloat(prog, "blindness", blind);
        setFloat(prog, "maxBlindnessDarkness", blind);
        setFloat(prog, "darknessFactor", 0f);
        setFloat(prog, "nightVision",
            (mc.player != null && mc.player.isPotionActive(net.minecraft.init.MobEffects.NIGHT_VISION)) ? 1f : 0f);
    }

    // -------------------------------------------------------------------------
    // GL uniform setters — all silently skip if location == -1
    // -------------------------------------------------------------------------

    static void setFloat(ShaderProgram prog, String name, float v) {
        int loc = prog.getUniform(name);
        if (loc < 0) return;
        // Convert if the pack declared this scalar as int (type mismatch = GL error)
        int type = prog.getUniformType(name);
        if (type == GL11.GL_INT || type == GL20.GL_BOOL) GL20.glUniform1i(loc, (int) v);
        else GL20.glUniform1f(loc, v);
    }

    static void setInt(ShaderProgram prog, String name, int v) {
        int loc = prog.getUniform(name);
        if (loc < 0) return;
        // Convert if the pack declared this scalar as float
        int type = prog.getUniformType(name);
        if (type == GL11.GL_FLOAT) GL20.glUniform1f(loc, v);
        else GL20.glUniform1i(loc, v);
    }

    // Shadow depth samplers route by DECLARED type, not name: unit SHADOWTEX0
    // holds the compare-mode depth texture (for sampler2DShadow / shadow2D),
    // unit SHADOWTEX1 the raw copy (for sampler2D / texelFetch). Packs mix
    // freely — Sildur's declares shadowtex1 as sampler2DShadow and shadowtex0
    // as plain sampler2D in composite; shadow2D on a non-compare texture (and
    // texelFetch on a compare one) is undefined and reads as 0 on Mesa, which
    // multiplied the whole scene to black.
    static void setShadowSampler(ShaderProgram prog, String name) {
        int loc = prog.getUniform(name);
        if (loc < 0) return;
        boolean compare = prog.getUniformType(name) == GL20.GL_SAMPLER_2D_SHADOW;
        GL20.glUniform1i(loc, compare ? MultiAttachmentFbo.UNIT_SHADOWTEX0
                                      : MultiAttachmentFbo.UNIT_SHADOWTEX1);
    }

    static void setVec3(ShaderProgram prog, String name, float x, float y, float z) {
        int loc = prog.getUniform(name);
        if (loc >= 0) GL20.glUniform3f(loc, x, y, z);
    }

    static void setIVec2(ShaderProgram prog, String name, int x, int y) {
        int loc = prog.getUniform(name);
        if (loc >= 0) GL20.glUniform2i(loc, x, y);
    }

    static void setMat4(ShaderProgram prog, String name, float[] mat) {
        int loc = prog.getUniform(name);
        if (loc < 0) return;
        UNI_BUF.clear();
        UNI_BUF.put(mat).flip();
        GL20.glUniformMatrix4(loc, false, UNI_BUF);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Transforms a world-space direction into view (eye) space using the
     * rotation-only part of a column-major OpenGL modelView matrix.
     * Translation (col 3) is intentionally ignored — directions have no position.
     *
     * Column-major layout: mat[col*4 + row].
     * Result = upper-left 3×3 of mat · (wx, wy, wz).
     */
    /** Temporary Sildur's diagnosis probe: sun/up vectors as uploaded + eye brightness. */
    public static String debugSunString() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return "no-world";
        float ca = mc.world.getCelestialAngle(0f);
        float sa = ca * 2f * (float) Math.PI;
        float sx = -MathHelper.sin(sa), sy = MathHelper.cos(sa);
        float[] sun = dirToViewSpace(modelView, sx * 100f, sy * 100f, 0f);
        float[] up  = dirToViewSpace(modelView, 0f, 100f, 0f);
        float sl = (float) Math.sqrt(sun[0]*sun[0] + sun[1]*sun[1] + sun[2]*sun[2]);
        float ul = (float) Math.sqrt(up[0]*up[0] + up[1]*up[1] + up[2]*up[2]);
        float sdotu = (sun[0]*up[0] + sun[1]*up[1] + sun[2]*up[2]) / (sl * ul);
        return String.format(
            "ca=%.3f sunView=[%.1f,%.1f,%.1f] upView=[%.1f,%.1f,%.1f] SdotU=%.3f eyeBS=[%d,%d] wt=%d",
            ca, sun[0], sun[1], sun[2], up[0], up[1], up[2], sdotu,
            Math.round(eyeBrightSmoothX), Math.round(eyeBrightSmoothY),
            (int) (mc.world.getWorldTime() % 24000));
    }

    private static float[] dirToViewSpace(float[] mat, float wx, float wy, float wz) {
        return new float[]{
            mat[0]*wx + mat[4]*wy + mat[8]*wz,
            mat[1]*wx + mat[5]*wy + mat[9]*wz,
            mat[2]*wx + mat[6]*wy + mat[10]*wz
        };
    }

    // -------------------------------------------------------------------------
    // 4x4 column-major matrix inverse (Gauss-Jordan)
    // -------------------------------------------------------------------------

    private static void invert(float[] src, float[] dst) {
        Matrix4f m = new Matrix4f();
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        buf.put(src).flip();
        m.load(buf);
        Matrix4f inv = Matrix4f.invert(m, null);
        if (inv == null) {
            // Non-invertible — identity fallback
            for (int i = 0; i < 16; i++) dst[i] = (i % 5 == 0) ? 1f : 0f;
            return;
        }
        buf.clear();
        inv.store(buf);
        buf.flip();
        buf.get(dst);
    }
}
