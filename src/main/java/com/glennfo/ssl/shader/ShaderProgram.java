package com.glennfo.ssl.shader;

import com.glennfo.ssl.SimpletonShaderLoader;
import org.lwjgl.opengl.GL20;

public class ShaderProgram {

    private final int programId;
    private final String name;
    private final int[] drawBuffers; // colortex indices from DRAWBUFFERS comment

    // Standard OptiFine uniforms injected into every shader before compilation.
    // Shaders reference these without declaring them (OptiFine/Iris injects them).
    // Each entry: { glsl-type, name }
    private static final String[][] INJECTED_UNIFORMS = {
        {"mat4",      "projectionMatrix"},
        {"mat4",      "projectionMatrixInverse"},
        {"mat4",      "modelViewMatrix"},
        {"mat4",      "modelViewMatrixInverse"},
        {"mat4",      "gbufferProjection"},
        {"mat4",      "gbufferProjectionInverse"},
        {"mat4",      "gbufferModelView"},
        {"mat4",      "gbufferModelViewInverse"},
        {"mat4",      "shadowProjection"},
        {"mat4",      "shadowProjectionInverse"},
        {"mat4",      "shadowModelView"},
        {"mat4",      "shadowModelViewInverse"},
        {"float",     "viewWidth"},
        {"float",     "viewHeight"},
        {"float",     "aspectRatio"},
        {"float",     "near"},
        {"float",     "far"},
        {"int",       "frameCounter"},
        {"float",     "frameTime"},
        {"float",     "sunAngle"},
        {"float",     "rainStrength"},
        {"float",     "weatherStrength"},
        {"vec3",      "cameraPosition"},
        {"vec3",      "previousCameraPosition"},
        {"vec3",      "sunPosition"},
        {"vec3",      "moonPosition"},
        {"vec3",      "shadowLightPosition"},
        {"vec3",      "upPosition"},
        {"sampler2D", "colortex0"},
        {"sampler2D", "colortex1"},
        {"sampler2D", "colortex2"},
        {"sampler2D", "colortex3"},
        {"sampler2D", "colortex4"},
        {"sampler2D", "colortex5"},
        {"sampler2D", "colortex6"},
        {"sampler2D", "colortex7"},
        {"sampler2D", "depthtex0"},
        {"sampler2D", "depthtex1"},
        {"sampler2D", "shadowtex0"},
        {"sampler2D", "shadowtex1"},
        {"sampler2D", "shadowcolor0"},
        // MC texture units injected for gbuffer shaders
        {"sampler2D", "tex"},         // unit 0 = block atlas (MC binds this)
        {"sampler2D", "lightmap"},    // unit 1 = lightmap   (MC binds this)
        {"sampler2D", "normals"},     // unit 2 = normal map (not provided, defaults to zero)
        {"sampler2D", "specular"},    // unit 3 = specular   (not provided)
        // gaux1-4 are aliases for colortex4-7
        {"sampler2D", "gaux1"},
        {"sampler2D", "gaux2"},
        {"sampler2D", "gaux3"},
        {"sampler2D", "gaux4"},
        {"sampler2D", "noisetex"},    // unit 15 = pack noise texture
        // Per-frame world state (beyond the matrices already above)
        {"int",       "worldTime"},
        {"int",       "worldDay"},
        {"int",       "moonPhase"},
        {"int",       "isEyeInWater"},
        {"float",     "wetness"},
        {"float",     "eyeAltitude"},
        {"float",     "screenBrightness"},
        {"float",     "frameTimeCounter"},
        {"vec3",      "fogColor"},
        {"vec3",      "skyColor"},
        {"ivec2",     "eyeBrightness"},
        {"ivec2",     "eyeBrightnessSmooth"},
        {"vec3",      "eyePosition"},
        {"mat4",      "gbufferPreviousModelView"},
        {"mat4",      "gbufferPreviousProjection"},
    };

    /**
     * Injects standard OptiFine uniform declarations after the #version line.
     *
     * Skips any uniform already declared in EITHER shader stage (declScan =
     * vertex + fragment source combined). Per-stage scanning caused link
     * failures: a pack declaring `sampler2DShadow shadowtex0` in the fragment
     * stage only would get our `sampler2D shadowtex0` injected into the vertex
     * stage — same uniform, two types, GL link error (Sildur's black screen).
     * Detection: name followed immediately by ; (declaration sites), which
     * doesn't match usage sites like `name *` or `name,`.
     */
    private static String injectUniforms(String src, String declScan) {
        StringBuilder block = new StringBuilder();
        for (String[] u : INJECTED_UNIFORMS) {
            String name = u[1];
            // A declared uniform will have the name followed by ; or [
            // A mere usage won't. This avoids injecting duplicates.
            if (!declScan.contains(" " + name + ";") && !declScan.contains("\t" + name + ";")) {
                block.append("uniform ").append(u[0]).append(' ').append(name).append(";\n");
            }
        }
        if (block.length() == 0) return src;

        // Insert after the LAST top-of-file #version/#extension directive — GLSL
        // forbids #extension after any declaration, so injecting right after
        // #version breaks packs with #extension lines at the top (Sildur's
        // composite1). Mid-file #extension (already past declarations) is the
        // pack's own sin; don't let it drag our block down there.
        int firstDecl = src.length();
        int semi = src.indexOf(';');
        if (semi >= 0) firstDecl = semi;
        int brace = src.indexOf('{');
        if (brace >= 0 && brace < firstDecl) firstDecl = brace;

        int insertAfter = -1;
        int idx = src.indexOf("#version");
        if (idx >= 0 && idx < firstDecl) insertAfter = idx;
        int scan = 0;
        while (true) {
            int ext = src.indexOf("#extension", scan);
            if (ext < 0 || ext >= firstDecl) break;
            if (ext > insertAfter) insertAfter = ext;
            scan = ext + 1;
        }
        if (insertAfter < 0) {
            return block + src;
        }
        int lineEnd = src.indexOf('\n', insertAfter);
        if (lineEnd < 0) lineEnd = src.length() - 1;
        return src.substring(0, lineEnd + 1) + block + src.substring(lineEnd + 1);
    }

    public static ShaderProgram compile(String name, String vertSrc, String fragSrc) {
        String declScan = vertSrc + "\n" + fragSrc;
        int vert = compileShader(name, GL20.GL_VERTEX_SHADER, injectUniforms(vertSrc, declScan));
        int frag = compileShader(name, GL20.GL_FRAGMENT_SHADER, injectUniforms(fragSrc, declScan));

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(program, 512);
            GL20.glDeleteProgram(program);
            throw new RuntimeException("[SSL] Link failed for '" + name + "': " + log);
        }

        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        int[] drawBuffers = parseDrawBuffers(fragSrc);
        SimpletonShaderLoader.LOG.info("[SSL] Compiled shader program '{}' DRAWBUFFERS:{}", name,
            drawBuffersToString(drawBuffers));
        return new ShaderProgram(program, name, drawBuffers);
    }

    /**
     * Parses the first DRAWBUFFERS comment from the (preprocessed) fragment source.
     * Format: "DRAWBUFFERS:XYZ" where each digit is a colortex index (0-7).
     * Returns {0} if no comment is found (default to colortex0).
     */
    static int[] parseDrawBuffers(String fragSrc) {
        int idx = fragSrc.indexOf("DRAWBUFFERS:");
        if (idx < 0) return new int[]{0};
        int start = idx + 12;
        while (start < fragSrc.length() && fragSrc.charAt(start) == ' ') start++;
        StringBuilder digits = new StringBuilder();
        while (start < fragSrc.length() && Character.isDigit(fragSrc.charAt(start))) {
            digits.append(fragSrc.charAt(start++));
        }
        if (digits.length() == 0) return new int[]{0};
        int[] result = new int[digits.length()];
        for (int i = 0; i < digits.length(); i++) result[i] = digits.charAt(i) - '0';
        return result;
    }

    private static String drawBuffersToString(int[] db) {
        StringBuilder sb = new StringBuilder();
        for (int i : db) sb.append(i);
        return sb.toString();
    }

    private static int compileShader(String progName, int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(id, 512);
            GL20.glDeleteShader(id);
            String typeName = (type == GL20.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            throw new RuntimeException("[SSL] " + typeName + " compile failed for '" + progName + "': " + log);
        }
        return id;
    }

    private final java.util.Map<String, Integer> uniformCache = new java.util.HashMap<>();
    // Declared GLSL type per active uniform (GL_FLOAT, GL_INT, ...). Packs
    // disagree on scalar types (CU: `uniform float framemod8`, Sildur's:
    // `uniform int framemod8`) and glUniform1f on an int uniform is a GL error
    // that silently leaves the value at 0 — setters convert per declared type.
    private final java.util.Map<String, Integer> uniformTypes = new java.util.HashMap<>();

    private ShaderProgram(int programId, String name, int[] drawBuffers) {
        this.programId = programId;
        this.name = name;
        this.drawBuffers = drawBuffers;
        int count = GL20.glGetProgrami(programId, GL20.GL_ACTIVE_UNIFORMS);
        int maxLen = GL20.glGetProgrami(programId, GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH);
        for (int i = 0; i < count; i++) {
            String uname = GL20.glGetActiveUniform(programId, i, Math.max(maxLen, 1));
            int type = GL20.glGetActiveUniformType(programId, i);
            if (uname.endsWith("[0]")) uname = uname.substring(0, uname.length() - 3);
            uniformTypes.put(uname, type);
        }
    }

    /** Declared GL type of a uniform, or -1 if not active. */
    public int getUniformType(String uniformName) {
        Integer t = uniformTypes.get(uniformName);
        return t != null ? t : -1;
    }

    public void use() {
        GL20.glUseProgram(programId);
    }

    public void unuse() {
        GL20.glUseProgram(0);
    }

    public void delete() {
        GL20.glDeleteProgram(programId);
    }

    public int getId() { return programId; }
    public String getName() { return name; }
    public int[] getDrawBuffers() { return drawBuffers; }

    public int getUniform(String uniformName) {
        return uniformCache.computeIfAbsent(uniformName,
            n -> GL20.glGetUniformLocation(programId, n));
    }
}
