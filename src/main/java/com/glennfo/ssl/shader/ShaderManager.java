package com.glennfo.ssl.shader;

import com.glennfo.ssl.SimpletonShaderLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads and manages an OptiFine-format shader pack.
 *
 * Program resolution order per name (e.g. "gbuffers_terrain"):
 *   1. shaders/world{dim}/name.vsh + .fsh
 *   2. shaders/name.vsh + .fsh
 *
 * Composite passes run in sequence: composite → composite1 → ... → composite7
 * using a ping-pong FBO pair so each pass can read the previous output.
 */
public class ShaderManager {

    public static final ShaderManager INSTANCE = new ShaderManager();

    // All gbuffers + passes in pipeline order
    private static final String[] GBUFFERS_PROGRAMS = {
        "gbuffers_basic", "gbuffers_terrain", "gbuffers_water", "gbuffers_entities",
        "gbuffers_entities_glowing", "gbuffers_block", "gbuffers_beaconbeam",
        "gbuffers_textured", "gbuffers_skybasic", "gbuffers_skytextured",
        "gbuffers_clouds", "gbuffers_weather", "gbuffers_hand",
        "gbuffers_armor_glint", "gbuffers_damagedblock", "gbuffers_spidereyes",
        "gbuffers_lightning", "gbuffers_line"
    };
    // OptiFine's standard program fallback chain (shaders.txt): when a pack
    // doesn't ship a program, geometry routes through the parent instead of
    // rendering fixed-function. Packs rely on this — Sildur's has no
    // gbuffers_entities and expects entities to go through gbuffers_textured
    // (writing to the buffers its composite chain reads entities from).
    private static final Map<String, String> GBUFFER_FALLBACK = new HashMap<>();
    static {
        GBUFFER_FALLBACK.put("gbuffers_textured",         "gbuffers_basic");
        GBUFFER_FALLBACK.put("gbuffers_textured_lit",     "gbuffers_textured");
        GBUFFER_FALLBACK.put("gbuffers_skybasic",         "gbuffers_basic");
        GBUFFER_FALLBACK.put("gbuffers_skytextured",      "gbuffers_textured");
        GBUFFER_FALLBACK.put("gbuffers_clouds",           "gbuffers_textured");
        GBUFFER_FALLBACK.put("gbuffers_terrain",          "gbuffers_textured_lit");
        GBUFFER_FALLBACK.put("gbuffers_damagedblock",     "gbuffers_terrain");
        GBUFFER_FALLBACK.put("gbuffers_block",            "gbuffers_terrain");
        GBUFFER_FALLBACK.put("gbuffers_beaconbeam",       "gbuffers_textured");
        GBUFFER_FALLBACK.put("gbuffers_entities",         "gbuffers_textured_lit");
        GBUFFER_FALLBACK.put("gbuffers_entities_glowing", "gbuffers_entities");
        GBUFFER_FALLBACK.put("gbuffers_armor_glint",      "gbuffers_textured");
        GBUFFER_FALLBACK.put("gbuffers_spidereyes",       "gbuffers_textured");
        GBUFFER_FALLBACK.put("gbuffers_hand",             "gbuffers_textured_lit");
        GBUFFER_FALLBACK.put("gbuffers_weather",          "gbuffers_textured_lit");
        GBUFFER_FALLBACK.put("gbuffers_water",            "gbuffers_terrain");
        GBUFFER_FALLBACK.put("gbuffers_lightning",        "gbuffers_entities");
        GBUFFER_FALLBACK.put("gbuffers_line",             "gbuffers_basic");
    }

    private static final String[] DEFERRED_PROGRAMS  = { "deferred1" };
    private static final String[] COMPOSITE_PROGRAMS = {
        "composite", "composite1", "composite2", "composite3",
        "composite4", "composite5", "composite6", "composite7"
    };

    private final Map<String, ShaderProgram> programs = new HashMap<>();

    // Source resolver set during load — used by preprocessor
    private Function<String, String> sourceResolver;

    public enum Mode { OFF, DEFAULT, PACK }

    // Single multi-attachment FBO: colortex0-7 + depth.
    // World renders into attachment 0; composite passes read/write any attachment.
    private MultiAttachmentFbo colortexFbo = null;
    private boolean active = false;
    private Mode mode = Mode.OFF;
    private String activePackName = null;

    // Currently active gbuffer shader (null when no shader is active)
    private ShaderProgram activeGbuffer = null;

    // Set by runPostProcess, cleared by blitCompositeToFbo.
    // Allows ssl_blitIfNeeded (RETURN of updateCameraAndRender) to blit when
    // renderGameOverlay is skipped (F1 / hideGUI mode) so we don't get a black screen.
    private boolean needsBlit = false;

    // True only while renderWorldPass is active (beginScene → runPostProcess).
    // Guards beginGbufferRender so no gbuffer shader activates during GUI rendering.
    private boolean inScenePass = false;

    // Pack-provided noise texture (loaded from shaders/lib/textures/noise.png)
    private int noiseTexId = -1;

    // Per-buffer frame-start clear behavior, parsed from the pack's
    // `const bool colortexNClear = ...;` declarations (default: clear everything).
    // History buffers (TAA, temporal exposure) set this false and must persist.
    private final boolean[] clearFlags = new boolean[MultiAttachmentFbo.MAX_COLORTEX];
    private static final java.util.regex.Pattern CLEAR_FLAG_PATTERN = java.util.regex.Pattern.compile(
        "const\\s+bool\\s+(?:colortex([0-7])|gaux([1-4]))Clear\\s*=\\s*(true|false)");

    private void parseClearFlags(String src) {
        java.util.regex.Matcher m = CLEAR_FLAG_PATTERN.matcher(src);
        while (m.find()) {
            int slot = (m.group(1) != null) ? (m.group(1).charAt(0) - '0')
                                            : (m.group(2).charAt(0) - '1' + 4);
            clearFlags[slot] = Boolean.parseBoolean(m.group(3));
        }
    }

    // Per-buffer GL internal formats from `const int colortexNFormat = RGBA16F;`.
    // Defaulting everything to RGBA8 breaks packs badly: HDR color clamps to [0,1]
    // and SNORM normal buffers read back garbage → uniformly dark, gray lighting.
    private final int[] bufferFormats = new int[MultiAttachmentFbo.MAX_COLORTEX];
    private static final java.util.regex.Pattern FORMAT_PATTERN = java.util.regex.Pattern.compile(
        "const\\s+int\\s+(?:colortex([0-7])|gaux([1-4]))Format\\s*=\\s*([A-Z0-9_]+)");
    private static final Map<String, Integer> GL_FORMATS = new HashMap<>();
    static {
        GL_FORMATS.put("R8",             GL30.GL_R8);
        GL_FORMATS.put("RG8",            GL30.GL_RG8);
        GL_FORMATS.put("RGB8",           GL11.GL_RGB8);
        GL_FORMATS.put("RGBA8",          GL11.GL_RGBA8);
        GL_FORMATS.put("R16",            GL30.GL_R16);
        GL_FORMATS.put("RG16",           GL30.GL_RG16);
        GL_FORMATS.put("RGB16",          GL11.GL_RGB16);
        GL_FORMATS.put("RGBA16",         GL11.GL_RGBA16);
        GL_FORMATS.put("R16F",           GL30.GL_R16F);
        GL_FORMATS.put("RG16F",          GL30.GL_RG16F);
        GL_FORMATS.put("RGB16F",         GL30.GL_RGB16F);
        GL_FORMATS.put("RGBA16F",        GL30.GL_RGBA16F);
        GL_FORMATS.put("R32F",           GL30.GL_R32F);
        GL_FORMATS.put("RG32F",          GL30.GL_RG32F);
        GL_FORMATS.put("RGB32F",         GL30.GL_RGB32F);
        GL_FORMATS.put("RGBA32F",        GL30.GL_RGBA32F);
        GL_FORMATS.put("R11F_G11F_B10F", GL30.GL_R11F_G11F_B10F);
        GL_FORMATS.put("RGB10_A2",       GL11.GL_RGB10_A2);
        GL_FORMATS.put("R8_SNORM",       org.lwjgl.opengl.GL31.GL_R8_SNORM);
        GL_FORMATS.put("RG8_SNORM",      org.lwjgl.opengl.GL31.GL_RG8_SNORM);
        GL_FORMATS.put("RGB8_SNORM",     org.lwjgl.opengl.GL31.GL_RGB8_SNORM);
        GL_FORMATS.put("RGBA8_SNORM",    org.lwjgl.opengl.GL31.GL_RGBA8_SNORM);
    }

    private void parseFormats(String src) {
        java.util.regex.Matcher m = FORMAT_PATTERN.matcher(src);
        while (m.find()) {
            int slot = (m.group(1) != null) ? (m.group(1).charAt(0) - '0')
                                            : (m.group(2).charAt(0) - '1' + 4);
            Integer fmt = GL_FORMATS.get(m.group(3));
            if (fmt != null) {
                bufferFormats[slot] = fmt;
            } else {
                SimpletonShaderLoader.LOG.warn("[SSL] Unknown colortex{} format '{}' — using RGBA8", slot, m.group(3));
            }
        }
    }

    private ShaderManager() {}

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    public void loadFromGameDir() {
        File shaderpacks = new File(Minecraft.getMinecraft().mcDataDir, "shaderpacks");
        if (!shaderpacks.exists() || !shaderpacks.isDirectory()) {
            SimpletonShaderLoader.LOG.info("[SSL] No shaderpacks folder, shaders disabled");
            return;
        }
        File[] entries = shaderpacks.listFiles();
        if (entries == null || entries.length == 0) return;

        for (File f : entries) {
            if (f.getName().endsWith(".zip")) { loadZip(f); return; }
        }
        for (File f : entries) {
            if (f.isDirectory()) { loadFolder(f); return; }
        }
    }

    /** Default mode: unload all SSL shaders and let vanilla render normally. */
    public void setDefault() {
        unload();
        mode = Mode.DEFAULT;
        SimpletonShaderLoader.LOG.info("[SSL] Default mode — vanilla rendering");
    }

    private void loadZip(File zip) {
        SimpletonShaderLoader.LOG.info("[SSL] Loading: {}", zip.getName());
        try (ZipFile zf = new ZipFile(zip)) {
            sourceResolver = path -> {
                String p = path.startsWith("/") ? path.substring(1) : path;
                try { return readZipEntry(zf, "shaders/" + p); }
                catch (IOException e) { return null; }
            };
            loadAllPrograms((name, dim) -> {
                try {
                    String vert = firstNonNull(
                        readZipEntry(zf, "shaders/" + dim + "/" + name + ".vsh"),
                        readZipEntry(zf, "shaders/" + name + ".vsh")
                    );
                    String frag = firstNonNull(
                        readZipEntry(zf, "shaders/" + dim + "/" + name + ".fsh"),
                        readZipEntry(zf, "shaders/" + name + ".fsh")
                    );
                    return vert != null && frag != null ? new String[]{vert, frag} : null;
                } catch (IOException e) { return null; }
            });
            // Load noise texture if the pack ships one
            try {
                ZipEntry noiseEntry = zf.getEntry("shaders/lib/textures/noise.png");
                if (noiseEntry == null) noiseEntry = zf.getEntry("shaders/noise.png");
                if (noiseEntry != null) {
                    try (InputStream is = zf.getInputStream(noiseEntry)) {
                        noiseTexId = uploadNoiseTexture(is);
                        if (noiseTexId >= 0)
                            SimpletonShaderLoader.LOG.info("[SSL] Noise texture loaded (id={})", noiseTexId);
                    }
                }
            } catch (Exception e) {
                SimpletonShaderLoader.LOG.warn("[SSL] Could not load noise texture: {}", e.getMessage());
            }
        } catch (IOException e) {
            SimpletonShaderLoader.LOG.error("[SSL] Failed to open zip: {}", e.getMessage());
        }
        finalise(zip.getName());
    }

    private void loadFolder(File folder) {
        SimpletonShaderLoader.LOG.info("[SSL] Loading: {}", folder.getName());
        File shaders = new File(folder, "shaders");
        if (!shaders.exists()) {
            SimpletonShaderLoader.LOG.warn("[SSL] No shaders/ subfolder in {}", folder.getName());
            return;
        }
        sourceResolver = path -> {
            try {
                String p = path.startsWith("/") ? path.substring(1) : path;
                File f = new File(shaders, p);
                return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
            } catch (IOException e) { return null; }
        };
        loadAllPrograms((name, dim) -> {
            try {
                File vsh = firstExisting(new File(shaders, dim + "/" + name + ".vsh"), new File(shaders, name + ".vsh"));
                File fsh = firstExisting(new File(shaders, dim + "/" + name + ".fsh"), new File(shaders, name + ".fsh"));
                if (vsh == null || fsh == null) return null;
                return new String[]{
                    new String(Files.readAllBytes(vsh.toPath()), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(fsh.toPath()), StandardCharsets.UTF_8)
                };
            } catch (IOException e) { return null; }
        });
        finalise(folder.getName());
    }

    /**
     * dim is "world0" (overworld default during load — actual per-frame switching happens at render time).
     * We load the world0 variant for now; the mixin can swap programs per-dimension later.
     */
    private void loadAllPrograms(ProgramLoader loader) {
        Arrays.fill(clearFlags, true);
        Arrays.fill(bufferFormats, GL11.GL_RGBA8);
        String[] allNames = concat(GBUFFERS_PROGRAMS, DEFERRED_PROGRAMS, COMPOSITE_PROGRAMS, new String[]{"shadow", "final"});
        for (String name : allNames) {
            try {
                String[][] src = { loader.load(name, "world0") };
                if (src[0] == null) continue;
                String vert = GlslPreprocessor.process(src[0][0], sourceResolver);
                String frag = GlslPreprocessor.process(src[0][1], sourceResolver);
                vert = fixTerrainNormals(name, vert);
                // Pipeline consts can appear in any shader stage (often an included lib file)
                parseClearFlags(vert);
                parseClearFlags(frag);
                parseFormats(vert);
                parseFormats(frag);
                programs.put(name, ShaderProgram.compile(name, vert, frag));
            } catch (Exception e) {
                SimpletonShaderLoader.LOG.warn("[SSL] Skipping '{}': {}", name, e.getMessage());
            }
        }
    }

    // Programs rendered with MC's BLOCK vertex format, which has NO normal
    // attribute — gl_Normal reads leftover GL state there (camera-dependent
    // garbage), so pack diffuse = dot(normal, sunDir) fails and terrain goes
    // near-black. Until SSL extends the terrain vertex format (the OptiFine
    // shadersmod approach, same work as mc_Entity), substitute world-up:
    // correct for ground, and vertical faces keep MC's baked per-face shading
    // via gl_Color. Entities/hand/block-entities have real normals — untouched.
    private static final java.util.Set<String> BLOCK_FORMAT_PROGRAMS = new java.util.HashSet<>(Arrays.asList(
        "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout",
        "gbuffers_terrain_cutout_mip", "gbuffers_water", "gbuffers_damagedblock"));

    private static String fixTerrainNormals(String name, String vertSrc) {
        if (!BLOCK_FORMAT_PROGRAMS.contains(name)) return vertSrc;
        return vertSrc.replaceAll("\\bgl_Normal\\b", "vec3(0.0, 1.0, 0.0)");
    }

    /** Load a specific pack file or folder (called from the GUI). */
    public void loadPack(java.io.File pack) {
        unload();
        if (pack.getName().endsWith(".zip")) {
            loadZip(pack);
        } else {
            loadFolder(pack);
        }
    }

    private void finalise(String packName) {
        active = !programs.isEmpty();
        mode = active ? Mode.PACK : Mode.OFF;
        activePackName = active ? packName : null;
        sourceResolver = null;
        if (active) {
            ShadowRenderer.INSTANCE.ensureFbo();
            com.glennfo.ssl.compat.GnetumCompat.suspend();
        }
        StringBuilder persist = new StringBuilder();
        for (int i = 0; i < clearFlags.length; i++) if (!clearFlags[i]) persist.append(i);
        SimpletonShaderLoader.LOG.info("[SSL] Loaded {} programs from '{}' (persistent colortex: {})",
            programs.size(), packName, persist.length() > 0 ? persist : "none");
    }

    // -------------------------------------------------------------------------
    // FBO management
    // -------------------------------------------------------------------------

    public void ensureFramebuffers(int w, int h) {
        if (colortexFbo == null || !colortexFbo.matches(w, h)) {
            if (colortexFbo != null) colortexFbo.delete();
            colortexFbo = MultiAttachmentFbo.create(w, h, bufferFormats);
            SimpletonShaderLoader.LOG.info("[SSL] colortexFbo created ({}x{}, 8 attachments, formats 0x{})",
                w, h, Integer.toHexString(bufferFormats[0]));
        }
        // Cheap array copy; keeps flags current across pack reloads without FBO recreation
        colortexFbo.setClearFlags(clearFlags);
    }

    // -------------------------------------------------------------------------
    // Render pipeline
    // -------------------------------------------------------------------------

    /**
     * Bind the colortex FBO so MC renders the scene into colortex0.
     * Called at renderWorldPass HEAD.
     */
    // Armed at frame start (RenderTickEvent.START); consumed by the first
    // renderWorldPass of the frame. Extra world passes in the same frame (minimap
    // previews, culling mods, portal views) must NOT go through the shader
    // pipeline: they'd re-clear the scene FBO, re-run composites over the real
    // frame's history buffers, and hijack the FBO binding mid-HUD. They render
    // vanilla instead — which is what those callers expect anyway.
    private boolean scenePassArmed = false;

    public void onFrameStart() {
        scenePassArmed = true;
    }

    /** Consumes the per-frame arm. Returns true only for the frame's first world pass. */
    public boolean armScenePass() {
        if (!scenePassArmed) return false;
        scenePassArmed = false;
        return true;
    }

    public boolean isInScenePass() {
        return inScenePass;
    }

    public void beginScene() {
        needsBlit = false;
        inScenePass = true;
        deferredRan = false;
        colortexFbo.bindForScene();
    }

    // True once the mid-scene deferred chain has run this frame
    private boolean deferredRan = false;

    // Debug bisection modes (J key): 0 = normal, 1 = raw gbuffer output
    // (skip composites, passthrough-blit colortex0 — shows what world+deferred
    // rendering produced), 2 = raw scene only (skip deferred too).
    private int debugMode = 0;
    private long lastProbeNano = 0;

    public void cycleDebugMode() {
        debugMode = (debugMode + 1) % 3;
        String desc = debugMode == 0 ? "normal pipeline"
                    : debugMode == 1 ? "raw colortex0 after deferred (composites skipped)"
                    : "raw colortex0 scene only (deferred + composites skipped)";
        SimpletonShaderLoader.LOG.info("[SSL] Debug mode {}: {}", debugMode, desc);
        Minecraft mcInst = Minecraft.getMinecraft();
        if (mcInst.player != null) {
            mcInst.player.sendStatusMessage(
                new net.minecraft.util.text.TextComponentString("[SSL] Debug " + debugMode + ": " + desc), true);
        }
    }

    public int getDebugMode() { return debugMode; }

    /**
     * Runs the deferred passes mid-scene (between opaque and translucent
     * geometry, per OptiFine pipeline order), then restores GL/texture state so
     * MC can continue rendering the world on top of the deferred output.
     */
    private void runDeferredChain() {
        if (deferredRan || colortexFbo == null || debugMode == 2) return;
        deferredRan = true;
        boolean any = false;
        for (String n : DEFERRED_PROGRAMS) if (programs.containsKey(n)) { any = true; break; }
        if (!any) return;

        Minecraft mc = Minecraft.getMinecraft();
        colortexFbo.prepareCompositeChain();
        ShadowRenderer.INSTANCE.bindTextures();
        for (String n : DEFERRED_PROGRAMS) runCompositePass(n, mc);
        GL20.glUseProgram(0);

        // Resume world rendering over the deferred output
        colortexFbo.resumeScene();
        // prepareCompositeChain bound colortex to units 0-7 — restore MC's
        // expectations: block atlas on unit 0, lightmap on unit 1
        restoreTextureUnits();
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        mc.entityRenderer.enableLightmap();
    }

    /**
     * Re-bind colortexFbo without clearing, after MC internally re-binds its own
     * framebuffer (e.g. after renderEntityOutlineFramebuffer). This ensures that
     * weather and hand rendering still land in colortex0 rather than in FBO 1.
     */
    public void reClaimColortexFbo() {
        if (colortexFbo != null) colortexFbo.bindForDraw();
    }

    // -------------------------------------------------------------------------
    // Gbuffer shader activation
    // -------------------------------------------------------------------------

    /**
     * Activate a gbuffer shader program for a specific geometry pass.
     * Selects draw buffers per the program's DRAWBUFFERS declaration, uploads
     * standard uniforms, and binds the noise texture.
     *
     * If the named program doesn't exist in the current pack, the call is a
     * no-op so geometry renders with MC's fixed-function pipeline.
     */
    public void beginGbufferRender(String name) {
        if (colortexFbo == null || !inScenePass) return;
        if ("gbuffers_water".equals(name)) {
            // depthtex1 = depth without translucents: snapshot right before the
            // translucent layer draws (idempotent — first call per frame wins)
            colortexFbo.snapshotDepthOnce();
            // OptiFine pipeline order: deferred passes run between opaque and
            // translucent geometry — CU draws the sky and applies opaque lighting
            // in deferred1. Running it at frame end makes translucents blend
            // against unlit terrain and stamps sky over the blended result.
            runDeferredChain();
        }
        // Resolve through the fallback chain until a program the pack ships
        ShaderProgram prog = programs.get(name);
        while (prog == null) {
            name = GBUFFER_FALLBACK.get(name);
            if (name == null) return; // end of chain: fixed-function render
            prog = programs.get(name);
        }
        Minecraft mc = Minecraft.getMinecraft();
        // Set the draw buffers this pass writes to
        colortexFbo.bindForPass(prog.getDrawBuffers());
        // Clear Albedo's current-shader tracking before binding, or its per-chunk/
        // per-entity uniform pushes land on our program's locations
        com.glennfo.ssl.compat.AlbedoCompat.suppress();
        // Activate the program and upload per-frame uniforms
        prog.use();
        ShaderUniforms.populate(prog, mc);
        // Bind noise texture at unit 15 if available
        if (noiseTexId >= 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MultiAttachmentFbo.UNIT_NOISETEX);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseTexId);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        // Shadow textures must be bound before gbuffer rendering so shaders
        // that call DoLighting() (e.g. gbuffers_terrain) can sample shadowtex0.
        ShadowRenderer.INSTANCE.bindTextures();
        // Units 4-7: current colortex4-7 data for gbuffer shaders that sample it
        // (e.g. CU water reads colortex5 — scene image — for reflections).
        // Units 0-3 stay MC's (atlas, lightmap); units 4-7 are otherwise unused
        // during world rendering.
        for (int i = 4; i < MultiAttachmentFbo.MAX_COLORTEX; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colortexFbo.getCurrentColorTexId(i));
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        activeGbuffer = prog;
    }

    /**
     * Deactivate the current gbuffer shader and reset the draw buffer back to
     * attachment 0 only, so any unshaded MC rendering between passes still
     * writes to colortex0.
     */
    public void endGbufferRender() {
        if (activeGbuffer == null) return;
        // Also clears Albedo's tracking — it may have re-bound its program mid-pass
        // (profiler section hooks) and would otherwise push uniforms at program 0
        com.glennfo.ssl.compat.AlbedoCompat.suppress();
        GL20.glUseProgram(0);
        colortexFbo.bindForDraw();
        activeGbuffer = null;
    }

    // -------------------------------------------------------------------------
    // Noise texture loading
    // -------------------------------------------------------------------------

    private int uploadNoiseTexture(InputStream is) {
        try {
            BufferedImage img = ImageIO.read(is);
            if (img == null) return -1;
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int px : argb) {
                buf.put((byte)((px >> 16) & 0xFF)); // R
                buf.put((byte)((px >> 8)  & 0xFF)); // G
                buf.put((byte)(px         & 0xFF)); // B
                buf.put((byte)((px >> 24) & 0xFF)); // A
            }
            buf.flip();
            int id = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return id;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Run deferred + composite passes using the colortex FBO. Leaves sampler units
     * 0-12 bound to the composite output textures so blitCompositeToFbo can issue
     * the final pass without re-running the whole chain.
     *
     * Does NOT blit to mc.framebuffer — call blitCompositeToFbo() for that, AFTER
     * MC has had a chance to clear/rebind mc.framebuffer in updateCameraAndRender.
     */
    public void runPostProcess(int displayW, int displayH) {
        if (colortexFbo == null || !inScenePass) return;
        Minecraft mc = Minecraft.getMinecraft();

        colortexFbo.prepareCompositeChain();
        ShadowRenderer.INSTANCE.bindTextures();

        // [SSL-PROBE] temporary Sildur's black-terrain diagnosis (1/sec):
        // colortex0 = albedo (Sildur's: YCoCg in rg, material flag in b),
        // colortex1 = encoded normal xy + lightmap zw — the lighting inputs.
        if (System.nanoTime() - lastProbeNano > 1_000_000_000L) {
            lastProbeNano = System.nanoTime();
            float[] c0 = colortexFbo.debugReadCenter(0);
            float[] c1 = colortexFbo.debugReadCenter(1);
            ShadowRenderer sr = ShadowRenderer.INSTANCE;
            SimpletonShaderLoader.LOG.info(
                "[SSL-PROBE] c0=[{},{},{},{}] c1=[{},{},{},{}] sm=[{},{},{}] {}",
                String.format("%.3f", c0[0]), String.format("%.3f", c0[1]),
                String.format("%.3f", c0[2]), String.format("%.3f", c0[3]),
                String.format("%.3f", c1[0]), String.format("%.3f", c1[1]),
                String.format("%.3f", c1[2]), String.format("%.3f", c1[3]),
                String.format("%.3f", sr.debugReadDepth(1024, 1024)),
                String.format("%.3f", sr.debugReadDepth(512, 512)),
                String.format("%.3f", sr.debugReadDepth(1536, 1536)),
                ShaderUniforms.debugSunString());
        }

        // Deferred passes normally already ran mid-scene (before translucents);
        // run here only as a fallback if the frame had no gbuffer activity
        if (!deferredRan && debugMode != 2) {
            for (String name : DEFERRED_PROGRAMS) {
                runCompositePass(name, mc);
            }
        }
        if (debugMode == 0) {
            for (String name : COMPOSITE_PROGRAMS) {
                runCompositePass(name, mc);
            }
        }

        // Unbind colortex FBO so the ping textures can be sampled in blitCompositeToFbo.
        // Sampler units 0-12 are intentionally left bound.
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
        org.lwjgl.opengl.GL20.glUseProgram(0);
        inScenePass = false;
        needsBlit = true;
    }

    /**
     * Blit the composite output to targetFboId as the final pass.
     *
     * Called from the hook in updateCameraAndRender that fires AFTER MC clears
     * mc.framebuffer at offset 678 (mc.framebuffer.bindFramebuffer(true)), so our
     * world composite persists past MC's clear and appears behind the HUD/GUI.
     *
     * Rebinds sampler units before drawing because renderEntityOutlineFramebuffer
     * and mc.framebuffer.framebufferClear() both touch texture unit 0 between
     * runPostProcess() and this call.
     */
    /** Blit only if runPostProcess ran but blitCompositeToFbo hasn't been called yet (F1 / hideGUI path). */
    public void blitIfNeeded() {
        if (!needsBlit || !active) return;
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.shader.Framebuffer mcFb = mc.getFramebuffer();
        if (mcFb == null) return;
        blitCompositeToFbo(mc.displayWidth, mc.displayHeight, mcFb.framebufferObject);
    }

    public void blitCompositeToFbo(int displayW, int displayH, int targetFboId) {
        if (colortexFbo == null) return;
        needsBlit = false;
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.shader.Framebuffer mcFb = mc.getFramebuffer();
        int fbW = (mcFb != null) ? mcFb.framebufferWidth  : displayW;
        int fbH = (mcFb != null) ? mcFb.framebufferHeight : displayH;

        // Restore sampler units that may have been disturbed since runPostProcess()
        colortexFbo.rebindSamplerUnits();
        ShadowRenderer.INSTANCE.bindTextures();

        // targetFboId is already bound (MC just called bindFramebuffer(true)), but
        // we rebind explicitly and set the viewport in case it changed.
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, targetFboId);
        org.lwjgl.opengl.GL11.glViewport(0, 0, fbW, fbH);

        ShaderProgram finalProg = programs.get("final");
        if (finalProg != null && debugMode == 0) {
            drawCompositeQuad(finalProg, mc);
        } else {
            // Debug modes bypass the final program: show colortex0 as-is
            drawPassthroughQuad(colortexFbo.getCurrentColorTexId(0), fbW, fbH);
        }

        org.lwjgl.opengl.GL20.glUseProgram(0);
        restoreTextureUnits();
    }

    private void runCompositePass(String name, Minecraft mc) {
        ShaderProgram prog = programs.get(name);
        if (prog == null) return;
        int[] db = prog.getDrawBuffers();
        colortexFbo.bindForPass(db);
        drawCompositeQuad(prog, mc);
        // Swap ping↔pong for written slots so next pass reads the just-written data.
        // FBO must still be bound here (drawCompositeQuad doesn't touch it).
        colortexFbo.swapAfterPass(db);
    }

    /**
     * Draws a fullscreen quad through a post-process shader. The colortex
     * textures are already bound to their units by the caller; this just
     * activates the program, sets uniforms, and issues the draw call.
     */
    private void drawCompositeQuad(ShaderProgram prog, Minecraft mc) {
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glLoadIdentity();
        org.lwjgl.opengl.GL11.glOrtho(-1, 1, -1, 1, -1, 1);
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glLoadIdentity();
        // Texture matrix must be identity so gl_TextureMatrix[0] * gl_MultiTexCoord0 = texcoord as-is
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_TEXTURE);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glLoadIdentity();
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);

        // Avoid glPushAttrib(GL_ALL_ATTRIB_BITS) — it saves/restores texture bindings
        // which would silently undo our ping-pong scratch bindings.  Save only what we change.
        boolean depthTest  = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        boolean cullFace   = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        boolean blend      = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
        boolean alphaTest  = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        boolean scissor    = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
        // Use GlStateManager so the depthMask cache stays in sync with GPU state.
        // Direct glDepthMask(false) without a GlStateManager restore causes cache
        // divergence: MC's GlStateManager.depthMask(true) on the next frame sees
        // cache=true and skips the actual GL call, leaving depthMask=false on the GPU.
        // That makes glClear(GL_DEPTH_BUFFER_BIT) a no-op in bindForScene().
        GlStateManager.depthMask(false);

        prog.use();
        ShaderUniforms.populate(prog, mc);

        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glTexCoord2f(0, 0); org.lwjgl.opengl.GL11.glVertex2f(-1, -1);
        org.lwjgl.opengl.GL11.glTexCoord2f(1, 0); org.lwjgl.opengl.GL11.glVertex2f( 1, -1);
        org.lwjgl.opengl.GL11.glTexCoord2f(1, 1); org.lwjgl.opengl.GL11.glVertex2f( 1,  1);
        org.lwjgl.opengl.GL11.glTexCoord2f(0, 1); org.lwjgl.opengl.GL11.glVertex2f(-1,  1);
        org.lwjgl.opengl.GL11.glEnd();

        org.lwjgl.opengl.GL20.glUseProgram(0);
        if (depthTest) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        if (cullFace)  org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        if (blend)     org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        if (alphaTest) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        if (scissor)   org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        GlStateManager.depthMask(true); // restore — GlStateManager.depthMask(false) above updated the cache

        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_TEXTURE);
        org.lwjgl.opengl.GL11.glPopMatrix();
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        org.lwjgl.opengl.GL11.glPopMatrix();
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    /**
     * Unbind our custom textures so MC's HUD/GUI rendering can bind its own.
     *
     * Units 0-7 (colortex) MUST go through GlStateManager.bindTexture().
     * GlStateManager caches the bound texture per unit and skips glBindTexture if
     * unchanged. If we call glBindTexture directly (bypassing the cache), the cache
     * still thinks our colortex is bound. MC's next GlStateManager.bindTexture(atlasId)
     * sees cache==atlasId... no wait — it sees cache==colortexId != atlasId, so it WOULD
     * rebind. BUT: we called GL directly after MC's framebufferClear which DID go through
     * GlStateManager (updating cache to the MC framebuffer texture). Our direct unbind
     * then leaves GPU at 0 but cache still at mcFb texture. GlStateManager then skips
     * rebinding the atlas → atlas never bound → HUD renders with no texture → flashing.
     * Fix: go through GlStateManager for units 0-7 so the cache stays consistent.
     */
    private void restoreTextureUnits() {
        for (int i = 0; i <= 7; i++) {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + i);
            GlStateManager.bindTexture(0);
        }
        // Units 8-15 are shadow/depth/noise — MC GUI never touches these, direct unbind is fine.
        for (int i = 8; i <= 15; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Blit a single texture over the viewport without any shader, as a passthrough. */
    private void drawPassthroughQuad(int texId, int w, int h) {
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        org.lwjgl.opengl.GL11.glPushMatrix(); org.lwjgl.opengl.GL11.glLoadIdentity();
        org.lwjgl.opengl.GL11.glOrtho(0, 1, 0, 1, -1, 1);
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        org.lwjgl.opengl.GL11.glPushMatrix(); org.lwjgl.opengl.GL11.glLoadIdentity();
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ALL_ATTRIB_BITS);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
        // MC enables alpha-test for foliage cutouts; pixels with low alpha in colortex0
        // (sky cleared to alpha=0, additive star rendering) would be discarded without this.
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        // Scissor and color-mask are defensive — MC may leave these set after world rendering.
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glTexCoord2f(0,0); org.lwjgl.opengl.GL11.glVertex2f(0,0);
        org.lwjgl.opengl.GL11.glTexCoord2f(1,0); org.lwjgl.opengl.GL11.glVertex2f(1,0);
        org.lwjgl.opengl.GL11.glTexCoord2f(1,1); org.lwjgl.opengl.GL11.glVertex2f(1,1);
        org.lwjgl.opengl.GL11.glTexCoord2f(0,1); org.lwjgl.opengl.GL11.glVertex2f(0,1);
        org.lwjgl.opengl.GL11.glEnd();
        org.lwjgl.opengl.GL11.glPopAttrib();
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        org.lwjgl.opengl.GL11.glPopMatrix();
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ShaderProgram getProgram(String name) { return programs.get(name); }
    public MultiAttachmentFbo getColortexFbo() { return colortexFbo; }
    public boolean isActive() { return active; }
    public Mode getMode() { return mode; }
    /** Returns the display name of the currently loaded pack, or null if off. */
    public String getActivePackName() { return activePackName; }

    public void unload() {
        com.glennfo.ssl.compat.GnetumCompat.restore();
        programs.values().forEach(ShaderProgram::delete);
        programs.clear();
        if (colortexFbo != null) { colortexFbo.delete(); colortexFbo = null; }
        if (noiseTexId >= 0) { GL11.glDeleteTextures(noiseTexId); noiseTexId = -1; }
        ShadowRenderer.INSTANCE.delete();
        activeGbuffer = null;
        needsBlit = false;
        inScenePass = false;
        active = false;
        mode = Mode.OFF;
        activePackName = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface ProgramLoader {
        /** Returns [vertSrc, fragSrc] or null if the program doesn't exist in the pack. */
        String[] load(String name, String dim);
    }

    private static String readZipEntry(ZipFile zf, String path) throws IOException {
        ZipEntry e = zf.getEntry(path);
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            return new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String firstNonNull(String a, String b) { return a != null ? a : b; }

    private static File firstExisting(File a, File b) {
        if (a.exists()) return a;
        if (b.exists()) return b;
        return null;
    }

    @SafeVarargs
    private static <T> T[] concat(T[]... arrays) {
        int len = Arrays.stream(arrays).mapToInt(a -> a.length).sum();
        @SuppressWarnings("unchecked")
        T[] result = (T[]) java.lang.reflect.Array.newInstance(arrays[0].getClass().getComponentType(), len);
        int pos = 0;
        for (T[] a : arrays) { System.arraycopy(a, 0, result, pos, a.length); pos += a.length; }
        return result;
    }
}
