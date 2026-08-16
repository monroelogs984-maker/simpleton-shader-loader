package com.glennfo.ssl.gui;

import com.glennfo.ssl.shader.ShaderManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GuiShaders extends GuiScreen {

    private static final int BTN_DEFAULT = 0;
    private static final int BTN_OFF     = 1;
    private static final int BTN_DONE    = 999;
    private static final int BTN_PACK_BASE = 100;

    private final GuiScreen parent;
    private final List<File> packs = new ArrayList<>();

    public GuiShaders(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        packs.clear();
        File shaderpacks = new File(mc.mcDataDir, "shaderpacks");
        if (shaderpacks.exists() && shaderpacks.isDirectory()) {
            File[] entries = shaderpacks.listFiles();
            if (entries != null) {
                for (File f : entries) {
                    if (f.getName().endsWith(".zip") || f.isDirectory()) {
                        packs.add(f);
                    }
                }
            }
        }

        buttonList.clear();

        ShaderManager sm = ShaderManager.INSTANCE;
        ShaderManager.Mode mode = sm.getMode();
        String activePack = sm.getActivePackName();

        int cx = width / 2;
        int y = height / 6;

        // Default and Off side by side at top
        buttonList.add(new GuiButton(BTN_DEFAULT, cx - 102, y, 100, 20,
            mode == ShaderManager.Mode.DEFAULT ? "> Default <" : "Default"));
        buttonList.add(new GuiButton(BTN_OFF, cx + 2, y, 100, 20,
            mode == ShaderManager.Mode.OFF ? "> Off <" : "Off"));
        y += 28;

        // Divider label is drawn in drawScreen; just leave spacing
        y += 12;

        // Pack buttons — two columns
        for (int i = 0; i < packs.size(); i++) {
            String name = packs.get(i).getName();
            String label = name.equals(activePack) ? "> " + name + " <" : name;
            int col = i % 2;
            int row = i / 2;
            int bx = col == 0 ? cx - 102 : cx + 2;
            buttonList.add(new GuiButton(BTN_PACK_BASE + i, bx, y + row * 24, 100, 20, label));
        }

        buttonList.add(new GuiButton(BTN_DONE, cx - 50, height - 28, 100, 20, "Done"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == BTN_DEFAULT) {
            ShaderManager.INSTANCE.setDefault();
        } else if (button.id == BTN_OFF) {
            ShaderManager.INSTANCE.unload();
        } else if (button.id >= BTN_PACK_BASE) {
            ShaderManager.INSTANCE.loadPack(packs.get(button.id - BTN_PACK_BASE));
        }
        initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Shader Packs", width / 2, 16, 0xFFFFFF);

        int dividerY = height / 6 + 32;
        drawCenteredString(fontRenderer, "———  Shader Packs  ———",
            width / 2, dividerY, 0x888888);

        if (packs.isEmpty()) {
            drawCenteredString(fontRenderer, "No packs found in shaderpacks/",
                width / 2, dividerY + 16, 0x666666);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
