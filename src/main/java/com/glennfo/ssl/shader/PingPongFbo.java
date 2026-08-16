package com.glennfo.ssl.shader;

/**
 * Two FBOs that alternate as read/write targets across composite passes.
 * Pass N reads from read(), writes to write(); call swap() between passes.
 */
public class PingPongFbo {

    private final GlFramebuffer[] fbos = new GlFramebuffer[2];
    private int writeIndex = 0;

    public static PingPongFbo create(int width, int height) {
        PingPongFbo pp = new PingPongFbo();
        pp.fbos[0] = GlFramebuffer.create(width, height);
        pp.fbos[1] = GlFramebuffer.create(width, height);
        return pp;
    }

    private PingPongFbo() {}

    public GlFramebuffer write() { return fbos[writeIndex]; }
    public GlFramebuffer read()  { return fbos[writeIndex ^ 1]; }

    public void swap() { writeIndex ^= 1; }

    public boolean matches(int width, int height) {
        return fbos[0].matches(width, height);
    }

    public void delete() {
        fbos[0].delete();
        fbos[1].delete();
    }
}
