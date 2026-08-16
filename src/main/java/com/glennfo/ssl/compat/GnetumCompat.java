package com.glennfo.ssl.compat;

import com.glennfo.ssl.SimpletonShaderLoader;

import java.lang.reflect.Field;

/**
 * Soft-dependency compat for Gnetum (HUD caching, 1.12.2).
 *
 * Gnetum only renders the HUD on a fraction of frames and reuses a cached HUD
 * texture on the rest. SSL delivers the world composite to mc.framebuffer from
 * RenderGameOverlayEvent.Pre(ALL) — which only fires on Gnetum's HUD-refresh
 * frames. Every cached-HUD frame therefore presents a cleared framebuffer:
 * the screen strobes at (1 - refreshRatio) of the framerate.
 *
 * Fix: while a shader pack is active, force Gnetum's enabled flag to OFF
 * (runtime only — we save and restore the user's own value when SSL returns
 * to Default mode). With caching off, the HUD renders every frame and the
 * overlay event delivers the composite every frame.
 */
public final class GnetumCompat {

    private static boolean present;
    private static boolean suspended;
    private static Field valueField;      // TwoStateBoolean.value
    private static Object enabledHolder;  // Gnetum.config.enabled (TwoStateBoolean)
    private static Object offValue;       // AnyBooleanValue.OFF
    private static Object savedValue;     // user's original AnyBooleanValue

    private GnetumCompat() {}

    private static boolean resolve() {
        if (enabledHolder != null) return true;
        try {
            Class<?> gnetum = Class.forName("me.decce.gnetum.Gnetum");
            Object config = gnetum.getField("config").get(null);
            if (config == null) return false; // Gnetum not initialized yet
            enabledHolder = config.getClass().getField("enabled").get(config);
            valueField = enabledHolder.getClass().getField("value");
            offValue = Enum.valueOf(
                Class.forName("me.decce.gnetum.util.AnyBooleanValue").asSubclass(Enum.class), "OFF");
            return true;
        } catch (ClassNotFoundException e) {
            present = false;
            return false;
        } catch (Exception e) {
            present = false;
            SimpletonShaderLoader.LOG.warn("[SSL] Gnetum present but compat hook failed: {}", e.toString());
            return false;
        }
    }

    public static void init() {
        try {
            Class.forName("me.decce.gnetum.Gnetum");
            present = true;
            SimpletonShaderLoader.LOG.info("[SSL] Gnetum detected — HUD caching will be suspended while a shader pack is active");
        } catch (ClassNotFoundException e) {
            present = false;
        }
    }

    /** Force HUD caching off while a shader pack is active. */
    public static void suspend() {
        if (!present || suspended || !resolve()) return;
        try {
            savedValue = valueField.get(enabledHolder);
            valueField.set(enabledHolder, offValue);
            suspended = true;
            SimpletonShaderLoader.LOG.info("[SSL] Gnetum HUD caching suspended (was {})", savedValue);
        } catch (Exception e) {
            present = false;
            SimpletonShaderLoader.LOG.warn("[SSL] Gnetum suspend failed, disabling compat: {}", e.toString());
        }
    }

    /** Restore the user's own Gnetum setting (Default mode / pack unloaded). */
    public static void restore() {
        if (!suspended) return;
        try {
            valueField.set(enabledHolder, savedValue);
            suspended = false;
            SimpletonShaderLoader.LOG.info("[SSL] Gnetum HUD caching restored to {}", savedValue);
        } catch (Exception e) {
            SimpletonShaderLoader.LOG.warn("[SSL] Gnetum restore failed: {}", e.toString());
        }
    }
}
