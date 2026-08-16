package com.glennfo.ssl.mixin;

import com.glennfo.ssl.shader.ShaderManager;
import com.glennfo.ssl.shader.ShadowRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.RayTraceResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    // -------------------------------------------------------------------------
    // Block layer (terrain)
    //
    // func_174977_a = renderBlockLayer(BlockRenderLayer, double, int, Entity)
    // SOLID/CUTOUT/CUTOUT_MIPPED  → gbuffers_terrain
    // TRANSLUCENT                 → gbuffers_water
    // -------------------------------------------------------------------------

    @Inject(method = "func_174977_a", at = @At("HEAD"))
    private void ssl_beginBlockLayer(BlockRenderLayer layer, double d, int pass, Entity entity,
                                     CallbackInfoReturnable<Integer> cir) {
        if (!ShaderManager.INSTANCE.isActive() || ShadowRenderer.INSTANCE.isShadowPass) return;
        String prog = (layer == BlockRenderLayer.TRANSLUCENT) ? "gbuffers_water" : "gbuffers_terrain";
        ShaderManager.INSTANCE.beginGbufferRender(prog);
    }

    @Inject(method = "func_174977_a", at = @At("RETURN"))
    private void ssl_endBlockLayer(BlockRenderLayer layer, double d, int pass, Entity entity,
                                   CallbackInfoReturnable<Integer> cir) {
        if (!ShaderManager.INSTANCE.isActive() || ShadowRenderer.INSTANCE.isShadowPass) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }

    // -------------------------------------------------------------------------
    // Entity rendering
    //
    // func_180446_a = renderEntities(Entity, ICamera, float)
    // -------------------------------------------------------------------------

    @Inject(method = "func_180446_a", at = @At("HEAD"))
    private void ssl_beginEntities(Entity viewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || ShadowRenderer.INSTANCE.isShadowPass) return;
        ShaderManager.INSTANCE.beginGbufferRender("gbuffers_entities");
    }

    @Inject(method = "func_180446_a", at = @At("RETURN"))
    private void ssl_endEntities(Entity viewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }

    // -------------------------------------------------------------------------
    // Sky rendering
    //
    // func_174976_a = renderSky(float, int)
    // -------------------------------------------------------------------------

    @Inject(method = "func_174976_a", at = @At("HEAD"))
    private void ssl_beginSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || ShadowRenderer.INSTANCE.isShadowPass) return;
        ShaderManager.INSTANCE.beginGbufferRender("gbuffers_skybasic");
    }

    @Inject(method = "func_174976_a", at = @At("RETURN"))
    private void ssl_endSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }

    // -------------------------------------------------------------------------
    // Cloud rendering
    //
    // func_180447_b = renderClouds(float, int, double, double, double)
    // -------------------------------------------------------------------------

    @Inject(method = "func_180447_b", at = @At("HEAD"))
    private void ssl_beginClouds(float partialTicks, int pass, double cx, double cy, double cz, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || ShadowRenderer.INSTANCE.isShadowPass) return;
        ShaderManager.INSTANCE.beginGbufferRender("gbuffers_clouds");
    }

    @Inject(method = "func_180447_b", at = @At("RETURN"))
    private void ssl_endClouds(float partialTicks, int pass, double cx, double cy, double cz, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }

    // -------------------------------------------------------------------------
    // Line rendering (block selection box, debug outlines)
    //
    // func_72731_b = drawSelectionBox(EntityPlayer, RayTraceResult, int, float)
    // -------------------------------------------------------------------------

    @Inject(method = "func_72731_b", at = @At("HEAD"))
    private void ssl_beginLine(EntityPlayer player, RayTraceResult rtr, int subID, float partialTicks, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive() || ShadowRenderer.INSTANCE.isShadowPass) return;
        ShaderManager.INSTANCE.beginGbufferRender("gbuffers_line");
    }

    @Inject(method = "func_72731_b", at = @At("RETURN"))
    private void ssl_endLine(EntityPlayer player, RayTraceResult rtr, int subID, float partialTicks, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }
}
