package com.glennfo.ssl.handler;

import com.glennfo.ssl.gui.GuiShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class KeyHandler {

    public static final KeyBinding KEY_SHADERS = new KeyBinding(
        "key.ssl.shaders", Keyboard.KEY_K, "key.category.ssl");
    public static final KeyBinding KEY_DEBUG = new KeyBinding(
        "key.ssl.debug", Keyboard.KEY_J, "key.category.ssl");

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY_SHADERS);
        ClientRegistry.registerKeyBinding(KEY_DEBUG);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (KEY_SHADERS.isPressed()) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(new GuiShaders(mc.currentScreen));
        }
        if (KEY_DEBUG.isPressed()) {
            com.glennfo.ssl.shader.ShaderManager.INSTANCE.cycleDebugMode();
        }
    }
}
