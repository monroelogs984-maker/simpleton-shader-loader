package com.glennfo.ssl.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * A simple FBO wrapping a color + depth texture for shader pipeline use.
 */
public class GlFramebuffer {

    private final int fboId;
    private final int colorTexId;
    private final int depthTexId;
    private final int width;
    private final int height;

    public static GlFramebuffer create(int width, int height) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        int color = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, color, 0);

        int depth = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depth, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new RuntimeException("[SSL] Framebuffer incomplete: 0x" + Integer.toHexString(status));

        return new GlFramebuffer(fbo, color, depth, width, height);
    }

    private GlFramebuffer(int fboId, int colorTexId, int depthTexId, int width, int height) {
        this.fboId = fboId;
        this.colorTexId = colorTexId;
        this.depthTexId = depthTexId;
        this.width = width;
        this.height = height;
    }

    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
    }

    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public void delete() {
        GL30.glDeleteFramebuffers(fboId);
        GL11.glDeleteTextures(colorTexId);
        GL11.glDeleteTextures(depthTexId);
    }

    public int getFboId() { return fboId; }
    public int getColorTexId() { return colorTexId; }
    public int getDepthTexId() { return depthTexId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public boolean matches(int w, int h) { return width == w && height == h; }
}
