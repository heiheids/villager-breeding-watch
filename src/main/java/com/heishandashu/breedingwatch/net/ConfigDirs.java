package com.heishandashu.breedingwatch.net;

import java.nio.file.Path;

/**
 * 配置目录来源抽象。
 *
 * <p>Fabric 是 {@code FabricLoader.getInstance().getConfigDir()}，
 * NeoForge 是 {@code FMLPaths.CONFIGDIR.get()}。收在一处，其余代码不碰加载器。</p>
 */
public final class ConfigDirs {

    private static volatile Path configDir;

    private ConfigDirs() {}

    /** 入口里注入一次。 */
    public static void set(Path dir) {
        configDir = dir;
    }

    public static Path config() {
        Path d = configDir;
        if (d != null) {
            return d;
        }
        // 兜底：常规 .minecraft/config（加载器还没就绪时也不至于 NPE）
        return Path.of("config").toAbsolutePath();
    }
}
