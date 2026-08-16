package com.glennfo.ssl.shader;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

/**
 * FBO with 8 color textures (colortex0-7) + 1 depth texture.
 *
 * Each colortex slot has TWO physical textures: ping and pong.
 * Before the composite chain, the FBO is re-pointed at the pong set and
 * sampler units 0-7 are bound to the ping set (which has world rendering data).
 * Each composite pass writes into the pong texture for its draw targets.
 * After each pass, swapAfterPass() flips those slots: the newly written
 * texture becomes the sampler input, and the old sampler texture becomes
 * the next write target — no texture data copies, just attachment pointer swaps.
 *
 * This eliminates the GL feedback loop (same texture as FBO attachment AND
 * sampler input in the same draw call) without any GPU stalls.
 *
 * Standard OptiFine texture unit assignments:
 *   units 0-7  → colortex0-7
 *   unit  8    → depthtex0
 *   unit  9    → depthtex1
 *   unit  10   → shadowtex0
 *   unit  11   → shadowtex1
 *   unit  12   → shadowcolor0
 */
public class MultiAttachmentFbo {

    public static final int MAX_COLORTEX = 8;
    public static final int UNIT_DEPTHTEX0   = 8;
    public static final int UNIT_DEPTHTEX1   = 9;
    public static final int UNIT_SHADOWTEX0  = 10;
    public static final int UNIT_SHADOWTEX1  = 11;
    public static final int UNIT_SHADOWCOLOR = 12;
    public static final int UNIT_NOISETEX    = 15;

    private final int fboId;
    // Two physical textures per colortex slot
    private final int[] colorTexPing = new int[MAX_COLORTEX];
    private final int[] colorTexPong = new int[MAX_COLORTEX];
    // pingInSampler[i]: true = ping is the current read texture (sampler unit i),
    //                          pong is the current write texture (FBO attachment i).
    //                  false = inverted.
    private final boolean[] pingInSampler = new boolean[MAX_COLORTEX];
    // clearFlags[i]: whether colortex i is cleared at frame start. Packs disable
    // clearing for history buffers via `const bool colortexNClear = false;` —
    // TAA / temporal exposure blend against last frame's data, so wiping those
    // buffers every frame causes strobing.
    private final boolean[] clearFlags = new boolean[MAX_COLORTEX];
    private final int depthTexId;
    // depthtex1: snapshot of scene depth taken just BEFORE translucents render.
    // OptiFine semantics: depthtex0 = full depth, depthtex1 = pre-translucent depth.
    // Packs classify "is this pixel behind water/glass" by comparing the two —
    // binding the same texture to both makes every pixel read as translucent-covered
    // (sky-tinted world, clouds compositing over everything).
    private final int depthTex1Id;
    private final int depth1FboId;
    private boolean depthSnapshotTaken = false;
    private final int width, height;

    public static MultiAttachmentFbo create(int width, int height) {
        int[] formats = new int[MAX_COLORTEX];
        java.util.Arrays.fill(formats, GL11.GL_RGBA8);
        return create(width, height, formats);
    }

    /**
     * @param formats GL internal format per colortex slot (from the pack's
     *                colortexNFormat consts). Formats matter: packs store HDR color
     *                (float formats — clamps to [0,1] in RGBA8, killing lighting)
     *                and signed normals (SNORM — garbage in unsigned textures).
     */
    public static MultiAttachmentFbo create(int width, int height, int[] formats) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        int[] ping = new int[MAX_COLORTEX];
        int[] pong = new int[MAX_COLORTEX];
        for (int i = 0; i < MAX_COLORTEX; i++) {
            ping[i] = createColorTex(width, height, formats[i]);
            pong[i] = createColorTex(width, height, formats[i]);
            // Ping set is the initial FBO attachment (world renders into ping)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                GL11.GL_TEXTURE_2D, ping[i], 0);
        }

        int depth = createDepthTex(width, height);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, depth, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("[SSL] MultiAttachmentFbo incomplete: 0x" + Integer.toHexString(status));
        }

        // Second depth texture + depth-only FBO used as the blit target for the
        // pre-translucent depth snapshot (depthtex1)
        int depth1 = createDepthTex(width, height);
        int depth1Fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depth1Fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, depth1, 0);
        // No color attachments — required for completeness
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        int status1 = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        if (status1 != GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new RuntimeException("[SSL] depthtex1 FBO incomplete: 0x" + Integer.toHexString(status1));

        return new MultiAttachmentFbo(fbo, ping, pong, depth, depth1, depth1Fbo, width, height);
    }

    private static int createDepthTex(int width, int height) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, width, height, 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return tex;
    }

    private static int createColorTex(int width, int height, int internalFormat) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        // data is null, but format/type must still be legal for the internal format
        int baseFormat = baseFormatFor(internalFormat);
        int type = typeFor(internalFormat);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            baseFormat, type, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        return tex;
    }

    private static int baseFormatFor(int internalFormat) {
        switch (internalFormat) {
            case GL30.GL_R8: case GL30.GL_R16: case GL30.GL_R16F: case GL30.GL_R32F:
                return GL11.GL_RED;
            case GL30.GL_RG8: case GL30.GL_RG16: case GL30.GL_RG16F: case GL30.GL_RG32F:
                return GL30.GL_RG;
            case GL11.GL_RGB8: case GL11.GL_RGB16: case GL30.GL_RGB16F: case GL30.GL_RGB32F:
            case GL30.GL_R11F_G11F_B10F: case org.lwjgl.opengl.GL31.GL_RGB8_SNORM:
                return GL11.GL_RGB;
            default:
                return GL11.GL_RGBA;
        }
    }

    private static int typeFor(int internalFormat) {
        switch (internalFormat) {
            case GL30.GL_R16F: case GL30.GL_R32F: case GL30.GL_RG16F: case GL30.GL_RG32F:
            case GL30.GL_RGB16F: case GL30.GL_RGB32F: case GL30.GL_RGBA16F: case GL30.GL_RGBA32F:
            case GL30.GL_R11F_G11F_B10F:
                return GL11.GL_FLOAT;
            case org.lwjgl.opengl.GL31.GL_RGB8_SNORM: case org.lwjgl.opengl.GL31.GL_RGBA8_SNORM:
                return GL11.GL_BYTE;
            default:
                return GL11.GL_UNSIGNED_BYTE;
        }
    }

    private MultiAttachmentFbo(int fboId, int[] ping, int[] pong, int depthTexId,
                               int depthTex1Id, int depth1FboId, int width, int height) {
        this.fboId = fboId;
        System.arraycopy(ping, 0, colorTexPing, 0, MAX_COLORTEX);
        System.arraycopy(pong, 0, colorTexPong, 0, MAX_COLORTEX);
        this.depthTexId = depthTexId;
        this.depthTex1Id = depthTex1Id;
        this.depth1FboId = depth1FboId;
        this.width = width;
        this.height = height;
        // Initially ping is in sampler (but also in FBO — will be swapped by prepareCompositeChain)
        java.util.Arrays.fill(pingInSampler, true);
        java.util.Arrays.fill(clearFlags, true);
    }

    /** Sets per-buffer frame-start clear behavior (from the pack's colortexNClear consts). */
    public void setClearFlags(boolean[] flags) {
        System.arraycopy(flags, 0, clearFlags, 0, MAX_COLORTEX);
    }

    /**
     * Bind this FBO for the scene pass. Clears all 8 color attachments and
     * depth, then narrows the draw target to attachment 0 so vanilla rendering
     * writes only into colortex0 (ping set).
     */
    public void bindForScene() {
        // Normalize ping/pong so "ping" is the texture holding the latest data for
        // every slot, then attach the ping set for scene rendering. After the previous
        // frame's composite chain, a slot's freshest data may live in either physical
        // texture (pingInSampler tracks which). prepareCompositeChain() assumes
        // sampler = ping, so for persistent (non-cleared) history buffers the latest
        // frame's data must actually BE in ping — otherwise composites read a stale
        // texture on alternating frames, which strobes.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        for (int i = 0; i < MAX_COLORTEX; i++) {
            if (!pingInSampler[i]) {
                int t = colorTexPing[i];
                colorTexPing[i] = colorTexPong[i];
                colorTexPong[i] = t;
                pingInSampler[i] = true;
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                GL11.GL_TEXTURE_2D, colorTexPing[i], 0);
        }
        // Re-attach depth (detached during the composite chain to avoid a feedback loop)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, depthTexId, 0);
        GL11.glViewport(0, 0, width, height);
        // Clear only the buffers the pack allows (history buffers persist), depth always
        IntBuffer clearBufs = BufferUtils.createIntBuffer(MAX_COLORTEX);
        for (int i = 0; i < MAX_COLORTEX; i++) {
            if (clearFlags[i]) clearBufs.put(GL30.GL_COLOR_ATTACHMENT0 + i);
        }
        clearBufs.flip();
        if (clearBufs.hasRemaining()) {
            GL20.glDrawBuffers(clearBufs);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        } else {
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }
        // Narrow to attachment 0 for vanilla rendering
        IntBuffer oneBuf = BufferUtils.createIntBuffer(1);
        oneBuf.put(GL30.GL_COLOR_ATTACHMENT0).flip();
        GL20.glDrawBuffers(oneBuf);
        depthSnapshotTaken = false;
    }

    /**
     * Snapshot current scene depth into depthtex1. Call once per frame, right
     * before the translucent block layer renders. Idempotent per frame.
     * The main FBO must be bound (scene rendering in progress).
     */
    public void snapshotDepthOnce() {
        if (depthSnapshotTaken) return;
        depthSnapshotTaken = true;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depth1FboId);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
            GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        // Restore the main FBO as both read and draw (draw-buffer state is per-FBO)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
    }

    /**
     * Resume scene rendering after a mid-scene deferred chain. Attaches each
     * slot's current READ texture (the latest data, including deferred output)
     * so translucent geometry blends over the deferred-lit opaque scene, and
     * re-attaches depth for depth testing. Draw target narrowed to attachment 0;
     * gbuffer passes re-widen via bindForPass.
     */
    public void resumeScene() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        for (int i = 0; i < MAX_COLORTEX; i++) {
            int latest = pingInSampler[i] ? colorTexPing[i] : colorTexPong[i];
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                GL11.GL_TEXTURE_2D, latest, 0);
        }
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, depthTexId, 0);
        GL11.glViewport(0, 0, width, height);
        IntBuffer oneBuf = BufferUtils.createIntBuffer(1);
        oneBuf.put(GL30.GL_COLOR_ATTACHMENT0).flip();
        GL20.glDrawBuffers(oneBuf);
    }

    /**
     * Re-bind this FBO for continued scene rendering without clearing.
     */
    public void bindForDraw() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
        IntBuffer oneBuf = BufferUtils.createIntBuffer(1);
        oneBuf.put(GL30.GL_COLOR_ATTACHMENT0).flip();
        GL20.glDrawBuffers(oneBuf);
    }

    /**
     * Bind this FBO for a composite pass, routing gl_FragData[i] to the colortex
     * indices specified by the shader's DRAWBUFFERS declaration.
     *
     * Call prepareCompositeChain() before the first pass so that the FBO's
     * attachments are on the pong set and sampler units are on the ping set.
     */
    public void bindForPass(int[] drawBufferIndices) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
        IntBuffer buf = BufferUtils.createIntBuffer(drawBufferIndices.length);
        for (int idx : drawBufferIndices) buf.put(GL30.GL_COLOR_ATTACHMENT0 + idx);
        buf.flip();
        GL20.glDrawBuffers(buf);
    }

    /**
     * Called once before the composite chain starts.
     *
     * Switches each FBO attachment from its current texture (ping, which holds world data)
     * to the pong texture (ready to receive composite output). Binds the ping textures
     * to sampler units 0-7 (so composite shaders read the correct world data) and
     * the depth texture to units 8 and 9. Unbinds the FBO so the ping textures can
     * be sampled without a feedback loop.
     */
    public void prepareCompositeChain() {
        // Attach each slot's current WRITE texture (opposite of the read side) so
        // passes write into scratch, and bind the current READ textures (latest
        // data) to sampler units. Generic w.r.t. pingInSampler state because the
        // chain can run twice per frame: once mid-scene (deferred passes, before
        // translucents) and once at frame end (composites). bindForScene()
        // normalizes read=ping at frame start.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        for (int i = 0; i < MAX_COLORTEX; i++) {
            int writeTex = pingInSampler[i] ? colorTexPong[i] : colorTexPing[i];
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                GL11.GL_TEXTURE_2D, writeTex, 0);
        }
        // If the pre-translucent depth snapshot never happened this frame, fall
        // back to full depth. Must run BEFORE the depth detach — the blit reads
        // the attachment.
        if (!depthSnapshotTaken) snapshotDepthOnce();

        // Detach depth: composite passes sample this texture as depthtex0/1 while the
        // FBO stays bound for drawing — attached + sampled is a GL feedback loop
        // (undefined behavior, flickers on Mesa). Composites don't depth-test anyway.
        // bindForScene()/resumeScene() re-attach it.
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0); // unbind so textures can be sampled

        // Bind current read textures to sampler units (latest data)
        for (int i = 0; i < MAX_COLORTEX; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,
                pingInSampler[i] ? colorTexPing[i] : colorTexPong[i]);
        }
        // depthtex0 = full depth; depthtex1 = pre-translucent snapshot
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_DEPTHTEX0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexId);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_DEPTHTEX1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex1Id);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Called after each composite pass completes.
     *
     * For every colortex slot that was written by the pass (drawBufferIndices),
     * swaps which physical texture is the sampler input and which is the FBO
     * attachment. The freshly written texture becomes the new sampler input
     * (so subsequent passes read the updated data), and the old sampler texture
     * becomes the new FBO write target (ready for the next pass to overwrite).
     *
     * Cost: glFramebufferTexture2D + glBindTexture per written slot — no data copies.
     * The FBO must still be bound when this is called (i.e., call immediately after draw).
     */
    public void swapAfterPass(int[] drawBufferIndices) {
        for (int idx : drawBufferIndices) {
            pingInSampler[idx] = !pingInSampler[idx];
            // After the flip: the texture now IN the sampler slot is the one just written
            int newSamplerTex = pingInSampler[idx] ? colorTexPing[idx] : colorTexPong[idx];
            int newFboTex     = pingInSampler[idx] ? colorTexPong[idx] : colorTexPing[idx];
            // Update sampler unit
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + idx);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, newSamplerTex);
            // Update FBO attachment (FBO is still bound from bindForPass)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + idx,
                GL11.GL_TEXTURE_2D, newFboTex, 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Returns the current read texture for colortex N (the one with the latest composite output). */
    public int getCurrentColorTexId(int n) {
        return pingInSampler[n] ? colorTexPing[n] : colorTexPong[n];
    }

    /**
     * Re-binds the current read textures to their sampler units (units 0-7 for colortex,
     * 8/9 for depthtex). Call before the final pass if other rendering may have disturbed
     * the texture units since prepareCompositeChain() was called.
     */
    public void rebindSamplerUnits() {
        for (int i = 0; i < MAX_COLORTEX; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,
                pingInSampler[i] ? colorTexPing[i] : colorTexPong[i]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_DEPTHTEX0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexId);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_DEPTHTEX1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex1Id);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Debug: reads the center pixel of a slot's current-read texture. */
    public float[] debugReadCenter(int slot) {
        int tex = getCurrentColorTexId(slot);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, depth1FboId);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, tex, 0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        java.nio.FloatBuffer px = BufferUtils.createFloatBuffer(4);
        GL11.glReadPixels(width / 2, height / 2, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, px);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, 0, 0);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        return new float[]{px.get(0), px.get(1), px.get(2), px.get(3)};
    }

    public int getFboId()                { return fboId; }
    public int getColorTexId(int index)  { return getCurrentColorTexId(index); }
    public int getDepthTexId()           { return depthTexId; }
    public int getWidth()                { return width; }
    public int getHeight()               { return height; }

    public boolean matches(int w, int h) { return width == w && height == h; }

    public void delete() {
        GL30.glDeleteFramebuffers(fboId);
        GL30.glDeleteFramebuffers(depth1FboId);
        for (int id : colorTexPing) GL11.glDeleteTextures(id);
        for (int id : colorTexPong) GL11.glDeleteTextures(id);
        GL11.glDeleteTextures(depthTexId);
        GL11.glDeleteTextures(depthTex1Id);
    }
}
