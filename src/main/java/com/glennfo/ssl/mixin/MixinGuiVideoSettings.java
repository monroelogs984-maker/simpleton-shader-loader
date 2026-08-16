package com.glennfo.ssl.mixin;

import com.glennfo.ssl.gui.GuiShaders;
import com.glennfo.ssl.shader.ShaderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

// Extend GuiScreen so width, height, and buttonList are accessible by their MCP
// names — ForgeGradle reobfuscates them to the correct SRG names in the output
// JAR. @Shadow on parent-class fields fails without a refMap.
@Mixin(GuiVideoSettings.class)
public abstract class MixinGuiVideoSettings extends GuiScreen {

    private static final int SSL_BTN_ID = 0xBBB;

    @Inject(method = "func_73866_w_", at = @At("RETURN"))
    private void ssl_addShadersButton(CallbackInfo ci) {
        buttonList.add(0, new GuiButton(SSL_BTN_ID,
            width / 2 - 155, 8, 310, 20, shaderLabel()));
    }

    @Inject(method = "func_146284_a", at = @At("HEAD"), cancellable = true)
    private void ssl_onShadersButton(GuiButton button, CallbackInfo ci) throws IOException {
        if (button.id != SSL_BTN_ID) return;
        Minecraft.getMinecraft().displayGuiScreen(new GuiShaders(this));
        ci.cancel();
    }

    private static String shaderLabel() {
        switch (ShaderManager.INSTANCE.getMode()) {
            case DEFAULT: return "Shaders: Default";
            case PACK:    return "Shaders: " + ShaderManager.INSTANCE.getActivePackName();
            default:      return "Shaders: Off";
        }
    }
}
