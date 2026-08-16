package com.glennfo.ssl.mixin;

import com.glennfo.ssl.shader.ShaderManager;
import com.glennfo.ssl.shader.ShadowRenderer;
import com.glennfo.ssl.shader.ShaderUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    /** Capture camera matrices after MC sets them up — needed by composite shaders for position reconstruction. */
    @Inject(method = "func_175068_a",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/EntityRenderer;func_78479_a(FI)V",
                     shift = At.Shift.AFTER))
    private void ssl_captureMatrices(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || !ShaderManager.INSTANCE.isInScenePass()) return;
        ShaderUniforms.captureMatrices();
    }

    /**
     * Redirect world rendering into our scene FBO — but only for the frame's FIRST
     * world pass. Mods that re-render the world within the same frame (minimaps,
     * culling, portal previews) trigger extra renderWorldPass calls; those must
     * render vanilla, or they re-clear our FBO and re-run composites mid-frame.
     */
    @Inject(method = "func_175068_a", at = @At("HEAD"))
    private void ssl_beginScene(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        if (!ShaderManager.INSTANCE.armScenePass()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ShaderManager.INSTANCE.ensureFramebuffers(mc.displayWidth, mc.displayHeight);
        ShadowRenderer.INSTANCE.computeMatrices(mc, partialTicks);
        ShaderManager.INSTANCE.beginScene();
    }

    /**
     * MC calls mc.getFramebuffer().bindFramebuffer(true) inside renderWorldPass after
     * rendering entity outlines, breaking our colortexFbo redirect. Reclaim colortexFbo
     * immediately after so that weather and hand rendering still go into colortex0.
     */
    @Inject(method = "func_175068_a",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/shader/Framebuffer;func_147610_a(Z)V",
                     shift = At.Shift.AFTER))
    private void ssl_reclaimFbo(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || !ShaderManager.INSTANCE.isInScenePass()) return;
        ShaderManager.INSTANCE.reClaimColortexFbo();
    }

    // -------------------------------------------------------------------------
    // Hand rendering — func_78476_b = renderHand(float partialTicks, int pass)
    // -------------------------------------------------------------------------

    @Inject(method = "func_78476_b", at = @At("HEAD"))
    private void ssl_beginHand(float partialTicks, int pass, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        // beginGbufferRender is internally gated on inScenePass, so extra passes no-op
        ShaderManager.INSTANCE.beginGbufferRender("gbuffers_hand");
    }

    @Inject(method = "func_78476_b", at = @At("RETURN"))
    private void ssl_endHand(float partialTicks, int pass, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }

    /**
     * Fallback blit for when GuiIngame.renderGameOverlay is skipped.
     * RenderGameOverlayEvent.Pre only fires when renderGameOverlay runs — which MC skips
     * when hideGUI=true AND currentScreen==null (F1 key). Without this, F1 mode leaves
     * a black screen because the Forge event never triggers the blit.
     *
     * HEAD/RETURN on func_181560_a (updateCameraAndRender) are safe; only INVOKE-type
     * targets within it fail to remap under MixinBooter.
     *
     * ShaderManager.blitIfNeeded() is a no-op if RenderHandler already blitted
     * (normal frames), so there's no double-blit.
     */
    @Inject(method = "func_181560_a", at = @At("RETURN"))
    private void ssl_blitIfNeeded(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        // Only fire in genuine F1 mode (hideGUI=true AND no open screen).
        // RenderHandler handles all other cases via RenderGameOverlayEvent.Pre(ALL).
        // Firing unconditionally here would paint the composite OVER menus/screens
        // on any frame where RenderHandler set needsBlit=false but we fire anyway.
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.gameSettings.hideGUI || mc.currentScreen != null) return;
        ShaderManager.INSTANCE.blitIfNeeded();
    }

    /**
     * Stop geometry shaders, run shadow pass, then run the deferred/composite chain.
     * Does NOT blit to mc.framebuffer — RenderHandler.onPreRenderOverlay does that
     * after updateCameraAndRender clears mc.framebuffer (offset 678) and just before
     * the HUD draws (offset 754), so the world composite appears behind the HUD.
     */
    @Inject(method = "func_175068_a", at = @At("RETURN"))
    private void ssl_endScene(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || !ShaderManager.INSTANCE.isInScenePass()) return;
        GL20.glUseProgram(0);
        ShaderManager.INSTANCE.endGbufferRender();
        Minecraft mc = Minecraft.getMinecraft();
        ShadowRenderer.INSTANCE.renderPass(mc, partialTicks);
        ShadowRenderer.INSTANCE.restoreViewport(mc.displayWidth, mc.displayHeight);
        ShaderManager.INSTANCE.runPostProcess(mc.displayWidth, mc.displayHeight);
    }
}
