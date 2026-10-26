package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * 客户端设置：{@code config/villagerbreedingwatch/client.json}。
 *
 * <p>单机时客户端与服务端在同一进程，联机时两边各存一份；扫描相关字段会随 prefs
 * 包上报给服务端，显示相关字段只在本机生效。</p>
 */
public final class ClientSettings {

    /** 总开关：整套观测的总闸。 */
    public boolean enabled = false;
    /** 头顶名牌：村民头上显示 #编号 + ♥/✕ + 缺什么。 */
    public boolean nametags = true;
    /** 村民 -> 床 的彩色射线。 */
    public boolean bedLines = true;
    /** 射线穿透方块显示。 */
    public boolean seeThrough = true;
    /** 左上角 HUD 面板。 */
    public boolean hud = true;
    /** 常开所有射线（不限半径、上限 600）。 */
    public boolean allLines = false;
    /** 常开所有头顶信息（不限半径）。 */
    public boolean allTags = false;
    /** 留空 = 跟随游戏语言；也可手动指定语言代码（如 en_us）。 */
    public String language = "";
    /** 扫描半径（格）。 */
    public int radius = 48;
    /** 扫描间隔（刻）。 */
    public int interval = 10;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(VbwMod.MOD_ID).resolve("client.json");
    }

    public void load() {
        Path f = file();
        if (!Files.exists(f)) {
            save();
            return;
        }
        try {
            SettingsDto d = Json.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), SettingsDto.class);
            if (d != null) {
                enabled = d.enabled();
                nametags = d.nametags();
                bedLines = d.bedLines();
                seeThrough = d.seeThrough();
                hud = d.hud();
                allLines = d.allLines();
                allTags = d.allTags();
                language = d.language() == null ? "" : d.language();
                radius = Math.max(8, Math.min(112, d.radius()));
                interval = Math.max(2, Math.min(100, d.interval()));
            }
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 读取客户端设置失败，使用默认值：{}", e.toString());
        }
    }

    public void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.GSON.toJson(new SettingsDto(enabled, nametags, bedLines, seeThrough, hud,
                    allLines, allTags, language, radius, interval)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 保存客户端设置失败：{}", e.toString());
        }
    }

    /** 生效的语言代码：手动指定优先，否则跟随游戏语言。 */
    public String effectiveLanguage() {
        if (language != null && !language.isBlank()) {
            return Lang.normalize(language);
        }
        return gameLanguage();
    }

    public static String gameLanguage() {
        try {
            String code = Minecraft.getInstance().options.languageCode;
            return code == null || code.isBlank() ? Lang.PRIMARY : Lang.normalize(code);
        } catch (Throwable t) {
            return Lang.PRIMARY;
        }
    }

    /** 同步给服务端的偏好（含语言代码，服务端按玩家语言生成文案）。 */
    public String prefsJson() {
        return "{\"c\":\"prefs\",\"enabled\":" + enabled
                + ",\"allLines\":" + allLines
                + ",\"allTags\":" + allTags
                + ",\"radius\":" + radius
                + ",\"interval\":" + interval
                + ",\"code\":\"" + effectiveLanguage() + "\"}";
    }

    public boolean wantTags() {
        return nametags && (enabled || allTags);
    }

    public boolean wantLines() {
        return bedLines && (enabled || allLines);
    }

    /** 存盘结构（public record，Gson 直接读写）。 */
    public record SettingsDto(boolean enabled, boolean nametags, boolean bedLines, boolean seeThrough,
                              boolean hud, boolean allLines, boolean allTags, String language,
                              int radius, int interval) {}
}
