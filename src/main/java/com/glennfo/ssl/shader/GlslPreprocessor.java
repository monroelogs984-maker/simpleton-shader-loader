package com.glennfo.ssl.shader;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves #include "path" directives in GLSL source.
 * The resolver function maps a relative path (e.g. "lib/Utility.glsl")
 * to its source string, or null if not found.
 */
public class GlslPreprocessor {

    private static final Pattern INCLUDE = Pattern.compile("^[ \t]*#include[ \t]+\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern VERSION = Pattern.compile("^[ \t]*#version[ \t]+.*$", Pattern.MULTILINE);

    public static String process(String source, Function<String, String> resolver) {
        return processRecursive(source, resolver, new HashSet<>(), true);
    }

    private static String processRecursive(String source, Function<String, String> resolver,
                                           Set<String> includeStack, boolean isRoot) {
        // Extract and re-insert #version at the very top (must be line 1 in the final output)
        String versionLine = "";
        if (isRoot) {
            Matcher vm = VERSION.matcher(source);
            if (vm.find()) {
                versionLine = vm.group() + "\n";
                source = source.substring(0, vm.start()) + source.substring(vm.end());
            }
        } else {
            // Strip #version from included files — only the root program's version counts
            source = VERSION.matcher(source).replaceAll("");
        }

        StringBuffer sb = new StringBuffer();
        Matcher m = INCLUDE.matcher(source);
        while (m.find()) {
            String path = m.group(1);
            if (includeStack.contains(path)) {
                m.appendReplacement(sb, "// [SSL] Skipped circular include: " + path);
                continue;
            }
            String included = resolver.apply(path);
            if (included == null) {
                m.appendReplacement(sb, "// [SSL] Missing include: " + path);
            } else {
                includeStack.add(path);
                String expanded = processRecursive(included, resolver, includeStack, false);
                includeStack.remove(path);
                m.appendReplacement(sb, Matcher.quoteReplacement(expanded));
            }
        }
        m.appendTail(sb);

        return isRoot ? versionLine + sb : sb.toString();
    }
}
