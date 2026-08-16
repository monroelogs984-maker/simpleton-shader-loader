package com.glennfo.ssl.mixin;

import com.glennfo.ssl.shader.ShaderManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public class MixinItemRenderer {

    // func_78440_a = renderItemInFirstPerson(float partialTicks)
    @Inject(method = "func_78440_a", at = @At("HEAD"))
    private void ssl_beginHand(float partialTicks, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.beginGbufferRender("gbuffers_hand");
    }

    @Inject(method = "func_78440_a", at = @At("RETURN"))
    private void ssl_endHand(float partialTicks, CallbackInfo ci) {
        if (!ShaderManager.INSTANCE.isActive()) return;
        ShaderManager.INSTANCE.endGbufferRender();
    }
}
