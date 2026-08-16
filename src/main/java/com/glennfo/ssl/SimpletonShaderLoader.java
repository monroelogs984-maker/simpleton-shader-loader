package com.glennfo.ssl;

import com.glennfo.ssl.handler.KeyHandler;
import com.glennfo.ssl.handler.RenderHandler;
import com.glennfo.ssl.handler.VideoSettingsHandler;
import com.glennfo.ssl.shader.ShaderManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = SimpletonShaderLoader.MOD_ID, name = "Simpleton's Shader Loader", version = "0.1.0",
     clientSideOnly = true, guiFactory = "com.glennfo.ssl.gui.SSLGuiFactory")
public class SimpletonShaderLoader {

    public static final String MOD_ID = "ssl";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Mod.Instance
    public static SimpletonShaderLoader instance;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        KeyHandler keyHandler = new KeyHandler();
        KeyHandler.register();
        MinecraftForge.EVENT_BUS.register(keyHandler);
        MinecraftForge.EVENT_BUS.register(new VideoSettingsHandler());
        MinecraftForge.EVENT_BUS.register(new RenderHandler());
        com.glennfo.ssl.compat.AlbedoCompat.init();
        com.glennfo.ssl.compat.GnetumCompat.init();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        ShaderManager.INSTANCE.loadFromGameDir();
    }
}
