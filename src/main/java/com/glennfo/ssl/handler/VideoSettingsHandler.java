package com.glennfo.ssl.handler;

import com.glennfo.ssl.SimpletonShaderLoader;
import com.glennfo.ssl.gui.GuiShaders;
import com.glennfo.ssl.shader.ShaderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class VideoSettingsHandler {

    private static final int BTN_ID = 0xBBB; // well outside vanilla option IDs

    @SubscribeEvent
    public void onVideoSettingsInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.getGui() instanceof GuiVideoSettings)) return;

        GuiVideoSettings gui = (GuiVideoSettings) event.getGui();
        SimpletonShaderLoader.LOG.info("[SSL] Adding shaders button to Video Settings (w={} h={})", gui.width, gui.height);

        // Footer row alongside Done — this area always renders correctly
        event.getButtonList().add(new GuiButton(BTN_ID, gui.width / 2 - 310, gui.height - 27, 200, 20, buildLabel()));
    }

    @SubscribeEvent
    public void onVideoSettingsButton(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.getGui() instanceof GuiVideoSettings)) return;
        if (event.getButton().id != BTN_ID) return;

        event.setCanceled(true);
        Minecraft.getMinecraft().displayGuiScreen(new GuiShaders(event.getGui()));
    }

    private static String buildLabel() {
        ShaderManager sm = ShaderManager.INSTANCE;
        switch (sm.getMode()) {
            case DEFAULT: return "Shaders: Default";
            case PACK:    return "Shaders: " + sm.getActivePackName();
            default:      return "Shaders: Off";
        }
    }
}
