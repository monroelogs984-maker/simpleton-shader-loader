package com.glennfo.ssl.handler;

import com.glennfo.ssl.shader.ShaderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class RenderHandler {

    /**
     * START: arms the shader pipeline for the frame's first world pass. Extra
     * renderWorldPass calls in the same frame (minimaps, culling mods) render
     * vanilla instead of hijacking the pipeline.
     *
     * END: F1/hideGUI fallback blit. renderGameOverlay is skipped when
     * hideGUI=true and no screen is open, so the overlay event never fires and
     * the composite would never reach the screen. The old mixin fallback on
     * updateCameraAndRender RETURN never fired in production (silent remap
     * failure) — this Forge event is the reliable path. fires after
     * updateCameraAndRender, before the buffer swap; nothing draws after it in
     * pure F1 mode, so blitting here can't overwrite menus.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        if (event.phase == TickEvent.Phase.START) {
            ShaderManager.INSTANCE.onFrameStart();
        } else {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.gameSettings.hideGUI && mc.currentScreen == null) {
                ShaderManager.INSTANCE.blitIfNeeded();
            }
        }
    }

    /**
     * Fires inside GuiIngame.renderGameOverlay, before any HUD element draws.
     *
     * In updateCameraAndRender, the sequence is:
     *   offset 678: mc.framebuffer.bindFramebuffer(true)  ← MC clears mc.framebuffer
     *   offset 754: GuiIngame.renderGameOverlay            ← this event fires here
     *   offset 878: ForgeHooksClient.drawScreen            ← pause menu / screens
     *
     * We blit the composite output here so it lands in mc.framebuffer AFTER MC's
     * clear but BEFORE the HUD and any open screens draw on top.
     */
    @SubscribeEvent
    public void onPreRenderOverlay(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!ShaderManager.INSTANCE.isActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer mcFb = mc.getFramebuffer();
        if (mcFb == null) return;
        ShaderManager.INSTANCE.blitCompositeToFbo(mc.displayWidth, mc.displayHeight, mcFb.framebufferObject);
    }
}
