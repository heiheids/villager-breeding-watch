package com.heishandashu.breedingwatch.i18n;

import com.google.gson.reflect.TypeToken;
import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 多语言文案。
 *
 * <p>文案来自 {@code config/villagerbreedingwatch/lang/&lt;语言代码&gt;.json}：
 * jar 内自带 zh_cn / en_us，首次运行释放到 config 目录，用户可自行编辑或新增语言。
 * 合并顺序是"jar 内置打底、用户文件覆盖"，升级模组新增的键不会缺文案。</p>
 *
 * <p>客户端按游戏语言自动选择；服务端按玩家上报的语言，为每个玩家的推送单独选文案
 * （{@link #use(String)} / {@link #release()} 走 ThreadLocal，服务端单线程 tick 内安全）。</p>
 */
public final class Lang {

    public static final String PRIMARY = "zh_cn";
    public static final String FALLBACK = "en_us";

    private static final Map<String, Map<String, String>> FILES = new ConcurrentHashMap<>();
    private static volatile String defaultCode = PRIMARY;
    private static final ThreadLocal<String> CUR = new ThreadLocal<>();

    private Lang() {}

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(VbwMod.MOD_ID).resolve("lang");
    }

    /** 释放内置默认文件（若不存在）并载入全部语言文件。 */
    public static void init() {
        try {
            installDefaults();
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 释放默认语言文件失败：{}", e.toString());
        }
        reload();
    }

    private static void installDefaults() {
        Path d = dir();
        try {
            Files.createDirectories(d);
            for (String code : List.of(PRIMARY, FALLBACK)) {
                Path out = d.resolve(code + ".json");
                if (Files.exists(out)) {
                    continue;
                }
                try (InputStream in = Lang.class.getResourceAsStream("/vbw_lang/" + code + ".json")) {
                    if (in != null) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        VbwMod.LOG.info("[VBW] 已释放默认语言文件 {}", out);
                    }
                }
            }
        } catch (IOException e) {
            VbwMod.LOG.warn("[VBW] 无法写入语言目录 {}：{}", d, e.toString());
        }
    }

    /** 重新读取 config 目录下的全部 *.json 语言文件。 */
    public static void reload() {
        Map<String, Map<String, String>> loaded = new ConcurrentHashMap<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.json")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    String code = name.substring(0, name.length() - 5).toLowerCase(Locale.ROOT).replace('-', '_');
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        Map<String, String> flat = new LinkedHashMap<>();
                        Map<String, Object> raw = Json.GSON.fromJson(text,
                                new TypeToken<Map<String, Object>>() {}.getType());
                        flatten("", raw, flat);
                        loaded.put(code, flat);
                    } catch (Exception e) {
                        VbwMod.LOG.warn("[VBW] 语言文件解析失败 {}：{}", p, e.toString());
                    }
                }
            } catch (IOException e) {
                VbwMod.LOG.warn("[VBW] 读取语言目录失败：{}", e.toString());
            }
        }
        for (String code : List.of(PRIMARY, FALLBACK)) {
            Map<String, String> base = builtin(code);
            Map<String, String> user = loaded.get(code);
            if (user == null) {
                loaded.put(code, base);
            } else {
                Map<String, String> merged = new LinkedHashMap<>(base);
                merged.putAll(user);
                loaded.put(code, merged);
            }
        }
        FILES.clear();
        FILES.putAll(loaded);
        VbwMod.LOG.info("[VBW] 已载入语言文件：{}", codes());
    }

    private static Map<String, String> builtin(String code) {
        Map<String, String> flat = new LinkedHashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream("/vbw_lang/" + code + ".json")) {
            if (in == null) {
                return flat;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> raw = Json.GSON.fromJson(text, new TypeToken<Map<String, Object>>() {}.getType());
            flatten("", raw, flat);
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 内置语言 {} 读取失败：{}", code, e.toString());
        }
        return flat;
    }

    /** 支持嵌套对象（{"cond":{"ADULT":"..."}}）与扁平键（{"cond.ADULT":"..."}）两种写法。 */
    private static void flatten(String prefix, Map<String, Object> src, Map<String, String> dst) {
        if (src == null) {
            return;
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("_")) {
                continue;   // 下划线开头是注释键
            }
            Object v = e.getValue();
            String full = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> sub = new LinkedHashMap<>();
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    sub.put(String.valueOf(en.getKey()), en.getValue());
                }
                flatten(full, sub, dst);
            } else if (v != null) {
                dst.put(full, String.valueOf(v));
            }
        }
    }

    public static List<String> codes() {
        return new ArrayList<>(FILES.keySet());
    }

    public static void setDefault(String code) {
        String c = normalize(code);
        if (c != null && !c.isEmpty()) {
            defaultCode = c;
        }
    }

    public static String defaultCode() {
        return defaultCode;
    }

    /** 本次求值/推送使用的语言（服务端按玩家设置，单线程内使用）。 */
    public static void use(String code) {
        String c = normalize(code);
        if (c == null || c.isEmpty()) {
            CUR.remove();
        } else {
            CUR.set(c);
        }
    }

    public static void release() {
        CUR.remove();
    }

    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return c.isEmpty() ? null : c;
    }

    public static String t(String key) {
        String s = lookup(currentCode(), key);
        if (s == null) {
            s = lookup(defaultCode, key);
        }
        if (s == null) {
            s = lookup(PRIMARY, key);
        }
        if (s == null) {
            s = lookup(FALLBACK, key);
        }
        return s != null ? s : key;
    }

    /** 带占位符的文案：模板里用 {0} {1} …。 */
    public static String f(String key, Object... args) {
        String s = t(key);
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    public static boolean has(String key) {
        return peek(key) != null;
    }

    /** 取文案，没有定义时返回 null（用于"有翻译就用、没有就用代码里的默认值"）。 */
    public static String peek(String key) {
        String s = lookup(currentCode(), key);
        if (s == null) {
            s = lookup(defaultCode, key);
        }
        if (s == null) {
            s = lookup(PRIMARY, key);
        }
        if (s == null) {
            s = lookup(FALLBACK, key);
        }
        return s;
    }

    private static String currentCode() {
        String code = CUR.get();
        return code == null ? defaultCode : code;
    }

    private static String lookup(String code, String key) {
        if (code == null || key == null) {
            return null;
        }
        Map<String, String> m = FILES.get(code);
        if (m == null) {
            // 未安装该语言：退到同语种（zh_tw -> zh_cn）
            int i = code.indexOf('_');
            if (i > 0) {
                String stem = code.substring(0, i);
                for (Map.Entry<String, Map<String, String>> e : FILES.entrySet()) {
                    if (e.getKey().equals(stem) || e.getKey().startsWith(stem + "_")) {
                        m = e.getValue();
                        break;
                    }
                }
            }
        }
        return m == null ? null : m.get(key);
    }

    /** 供设置界面展示：当前可用语言列表（代码 -> 条目数）。 */
    public static Map<String, Integer> inventory() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> e : FILES.entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }
}
