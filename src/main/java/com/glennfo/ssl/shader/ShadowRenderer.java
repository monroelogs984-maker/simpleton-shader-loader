package com.glennfo.ssl.shader;

import com.glennfo.ssl.SimpletonShaderLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;

import java.nio.FloatBuffer;

public class ShadowRenderer {

    public static final ShadowRenderer INSTANCE = new ShadowRenderer();

    private static final int   SHADOW_RES  = 2048;
    private static final float SHADOW_HALF = 192f;

    private int shadowFboId       = -1;
    private int shadowDepthTex    = -1;
    private int shadowColorTex    = -1;
    // Raw depth copy for shadowtex1: same depth data as shadowDepthTex but without
    // GL_COMPARE_R_TO_TEXTURE.  CU samples shadowtex1 as a plain sampler2D for raw
    // [0,1] depth reads; using a compare-mode texture as sampler2D is undefined.
    private int shadowRawFboId    = -1;
    private int shadowDepthTexRaw = -1;
    private boolean ready = false;

    // Set true while renderPass() is executing so gbuffer mixin hooks skip shader activation
    public boolean isShadowPass = false;

    // Full view matrix (rotation + player-position translation) used for shadow-pass rendering.
    // Separate from ShaderUniforms.shadowModelView which is rotation-only for shader sampling.
    private final float[] shadowViewFull = new float[16];

    private ShadowRenderer() {}

    // -------------------------------------------------------------------------
    // FBO lifecycle
    // -------------------------------------------------------------------------

    public void ensureFbo() {
        if (ready) return;

        shadowFboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFboId);

        // Depth texture with hardware PCF compare mode.
        // CU declares sampler2DShadow shadowtex0 and uses shadow2D() for percentage-closer
        // filtering. GL_COMPARE_R_TO_TEXTURE + GL_LEQUAL: result = (r <= depth_in_map) → 1.0 lit.
        // GL_CLAMP_TO_BORDER with border=1.0: UV outside [0,1] returns fully lit (no shadow),
        // preventing shadow acne on geometry outside the shadow frustum.
        shadowDepthTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24,
            SHADOW_RES, SHADOW_RES, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        FloatBuffer border = BufferUtils.createFloatBuffer(4);
        border.put(1f).put(1f).put(1f).put(1f).flip();
        GL11.glTexParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, border);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, shadowDepthTex, 0);

        // Color texture (translucent shadow tinting)
        shadowColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowColorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            SHADOW_RES, SHADOW_RES, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, shadowColorTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            SimpletonShaderLoader.LOG.error("[SSL] Shadow FBO incomplete: 0x{}", Integer.toHexString(status));
            return;
        }

        GL11.glClearDepth(1.0);
        GL11.glDepthMask(true);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // Raw depth FBO — same resolution, depth-only, no compare mode.
        // After each shadow pass we blit shadowDepthTex → shadowDepthTexRaw so
        // shadowtex1 carries the same depth data but accessible as plain floats.
        shadowDepthTexRaw = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTexRaw);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24,
            SHADOW_RES, SHADOW_RES, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        FloatBuffer border2 = BufferUtils.createFloatBuffer(4);
        border2.put(1f).put(1f).put(1f).put(1f).flip();
        GL11.glTexParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, border2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        shadowRawFboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowRawFboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, shadowDepthTexRaw, 0);
        GL11.glDrawBuffer(0);  // GL_NONE — depth-only FBO, no colour writes
        GL11.glReadBuffer(0);
        int rawStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (rawStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
            SimpletonShaderLoader.LOG.warn("[SSL] Shadow raw FBO incomplete: 0x{}", Integer.toHexString(rawStatus));
        }

        ready = true;
        SimpletonShaderLoader.LOG.info("[SSL] Shadow FBO created ({}x{})", SHADOW_RES, SHADOW_RES);
    }

    // -------------------------------------------------------------------------
    // Matrix computation
    // -------------------------------------------------------------------------

    private final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    public void computeMatrices(Minecraft mc, float partialTicks) {
        if (mc.world == null || mc.player == null) return;

        float sunAngle = mc.world.getCelestialAngle(partialTicks) * 2f * (float) Math.PI;
        float sx = -MathHelper.sin(sunAngle);
        float sy =  MathHelper.cos(sunAngle);
        if (mc.world.getCelestialAngle(partialTicks) > 0.5f) { sx = -sx; sy = -sy; }

        Vector3f lightDir = new Vector3f(sx, sy, 0f);
        lightDir.normalise(lightDir);

        float cx = (float) mc.player.posX;
        float cy = (float) mc.player.posY;
        float cz = (float) mc.player.posZ;

        Vector3f up = Math.abs(lightDir.y) < 0.999f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f right   = Vector3f.cross(lightDir, up, null);    right.normalise(right);
        Vector3f corrUp  = Vector3f.cross(right, lightDir, null); corrUp.normalise(corrUp);

        // Rotation-only view matrix for shader uniforms.
        // CU's composite shaders pass player-space coords (worldPos - cameraPos), so the
        // matrix must NOT include the camera translation — that offset is already baked in
        // by the shader.  Sending a full view matrix would double-translate and mis-place shadows.
        float[] viewRot = {
             right.x,   corrUp.x,  -lightDir.x, 0,
             right.y,   corrUp.y,  -lightDir.y, 0,
             right.z,   corrUp.z,  -lightDir.z, 0,
             0,         0,          0,           1
        };

        // Full view matrix (rotation + translation) for shadow-pass GL rendering.
        // renderBlockLayer uses world-space chunk coords, so the GL_MODELVIEW must include
        // the player-position translation to centre the shadow frustum on the player.
        float[] viewFull = {
             right.x,   corrUp.x,  -lightDir.x, 0,
             right.y,   corrUp.y,  -lightDir.y, 0,
             right.z,   corrUp.z,  -lightDir.z, 0,
            -dot(right,   cx, cy, cz),
            -dot(corrUp,  cx, cy, cz),
             dot(lightDir, cx, cy, cz), 1
        };

        float n = -SHADOW_HALF * 4f, f = SHADOW_HALF * 4f;
        float h = SHADOW_HALF, w = SHADOW_HALF;
        float[] proj = {
            1f/w, 0,     0,           0,
            0,    1f/h,  0,           0,
            0,    0,    -2f/(f-n),    0,
            0,    0,    -(f+n)/(f-n), 1
        };

        System.arraycopy(viewRot,  0, ShaderUniforms.shadowModelView,        0, 16);
        System.arraycopy(proj,     0, ShaderUniforms.shadowProjection,        0, 16);
        System.arraycopy(viewFull, 0, this.shadowViewFull,                    0, 16);
        invertInto(viewRot,  ShaderUniforms.shadowModelViewInverse);
        invertInto(proj,     ShaderUniforms.shadowProjectionInverse);
    }

    // -------------------------------------------------------------------------
    // Render pass
    // -------------------------------------------------------------------------

    public void renderPass(Minecraft mc, float partialTicks) {
        if (!ready || mc.renderGlobal == null || mc.player == null) return;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFboId);
        GL11.glViewport(0, 0, SHADOW_RES, SHADOW_RES);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        matBuf.clear(); matBuf.put(ShaderUniforms.shadowProjection).flip();
        GL11.glLoadMatrix(matBuf);

        // Use the full view matrix (with player-position translation) so that world-space
        // chunk geometry is correctly centred in the shadow frustum.
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        matBuf.clear(); matBuf.put(shadowViewFull).flip();
        GL11.glLoadMatrix(matBuf);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_FRONT);
        GL11.glDisable(GL11.GL_BLEND);

        ShaderProgram shadowProg = ShaderManager.INSTANCE.getProgram("shadow");
        if (shadowProg != null) {
            shadowProg.use();
            ShaderUniforms.populate(shadowProg, mc);
        }

        isShadowPass = true;
        mc.renderGlobal.renderBlockLayer(BlockRenderLayer.SOLID,        partialTicks, 0, mc.player);
        mc.renderGlobal.renderBlockLayer(BlockRenderLayer.CUTOUT_MIPPED, partialTicks, 0, mc.player);
        mc.renderGlobal.renderBlockLayer(BlockRenderLayer.CUTOUT,        partialTicks, 0, mc.player);
        isShadowPass = false;

        if (shadowProg != null) GL20.glUseProgram(0);
        GL11.glPopAttrib();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // Copy depth into the raw FBO so shadowtex1 holds the same data without compare mode.
        if (shadowRawFboId >= 0) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, shadowFboId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, shadowRawFboId);
            GL30.glBlitFramebuffer(0, 0, SHADOW_RES, SHADOW_RES, 0, 0, SHADOW_RES, SHADOW_RES,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }

    public void restoreViewport(int displayW, int displayH) {
        GL11.glViewport(0, 0, displayW, displayH);
    }

    public void bindTextures() {
        if (!ready) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MultiAttachmentFbo.UNIT_SHADOWTEX0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MultiAttachmentFbo.UNIT_SHADOWTEX1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTexRaw >= 0 ? shadowDepthTexRaw : shadowDepthTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MultiAttachmentFbo.UNIT_SHADOWCOLOR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowColorTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void delete() {
        if (!ready) return;
        GL30.glDeleteFramebuffers(shadowFboId);
        GL11.glDeleteTextures(shadowDepthTex);
        GL11.glDeleteTextures(shadowColorTex);
        if (shadowRawFboId >= 0)    { GL30.glDeleteFramebuffers(shadowRawFboId);  shadowRawFboId    = -1; }
        if (shadowDepthTexRaw >= 0) { GL11.glDeleteTextures(shadowDepthTexRaw);   shadowDepthTexRaw = -1; }
        ready = false;
    }

    /** Debug: reads a raw depth value from the shadow map (1.0 = empty/far). */
    public float debugReadDepth(int x, int y) {
        if (!ready) return -1f;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, shadowFboId);
        FloatBuffer buf = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        return buf.get(0);
    }

    public int getDepthTexId()  { return shadowDepthTex; }
    public int getColorTexId()  { return shadowColorTex; }
    public boolean isReady()    { return ready; }

    // -------------------------------------------------------------------------

    private static float dot(Vector3f v, float x, float y, float z) {
        return v.x * x + v.y * y + v.z * z;
    }

    private static void invertInto(float[] src, float[] dst) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        Matrix4f m = new Matrix4f();
        buf.put(src).flip(); m.load(buf);
        Matrix4f inv = Matrix4f.invert(m, null);
        if (inv == null) { for (int i = 0; i < 16; i++) dst[i] = (i % 5 == 0) ? 1f : 0f; return; }
        buf.clear(); inv.store(buf); buf.flip(); buf.get(dst);
    }
}
