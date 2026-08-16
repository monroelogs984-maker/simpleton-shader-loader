package com.glennfo.ssl.compat;

import com.glennfo.ssl.SimpletonShaderLoader;

import java.lang.reflect.Method;

/**
 * Soft-dependency compat for Albedo (colored lights, 1.12.2).
 *
 * Albedo tracks its "current shader" in static state and pushes per-chunk /
 * per-entity uniforms to whatever GL program is ACTUALLY bound, guarded only
 * by its own tracking. When SSL binds a gbuffer program over Albedo's, that
 * guard still passes and Albedo's writes land on SSL's uniform locations —
 * type mismatches raise GL_INVALID_OPERATION, type-compatible hits silently
 * corrupt samplers/matrices.
 *
 * Fix: call Albedo's ShaderManager.stopShader() whenever SSL binds a program
 * during world rendering. That clears Albedo's currentShader, so its event
 * handlers (all guarded by isCurrentShader) no-op for the rest of the pass.
 * Albedo's colored lighting is effectively suspended while a shader pack is
 * active — same trade-off as OptiFine shaders — and fully functional again
 * the moment SSL is in Default mode (where SSL never binds and this is never
 * reached).
 */
public final class AlbedoCompat {

    private static Method stopShader;
    private static boolean present;

    private AlbedoCompat() {}

    public static void init() {
        try {
            Class<?> sm = Class.forName("elucent.albedo.util.ShaderManager");
            stopShader = sm.getMethod("stopShader");
            present = true;
            SimpletonShaderLoader.LOG.info("[SSL] Albedo detected — colored lights will be suspended while a shader pack is active");
        } catch (ClassNotFoundException e) {
            present = false;
        } catch (Exception e) {
            present = false;
            SimpletonShaderLoader.LOG.warn("[SSL] Albedo present but compat hook failed: {}", e.toString());
        }
    }

    /** Clears Albedo's current-shader tracking so its uniform pushes no-op. */
    public static void suppress() {
        if (!present) return;
        try {
            stopShader.invoke(null);
        } catch (Exception e) {
            // Disarm on first failure rather than throwing every frame
            present = false;
            SimpletonShaderLoader.LOG.warn("[SSL] Albedo suppress failed, disabling compat: {}", e.toString());
        }
    }
}
