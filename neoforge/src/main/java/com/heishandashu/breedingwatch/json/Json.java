package com.heishandashu.breedingwatch.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.heishandashu.breedingwatch.i18n.Lang;
import java.util.Locale;

/**
 * 统一 JSON 入口。
 *
 * <p>26.3 自带的 Gson 是 2.13+，原生支持 record，不需要再手写 record 适配器
 * （1.21.1 时代那个反射坑在 26.3 已不存在）。约定：被读写的数据类型一律写成
 * public/包级 record，不要 private。</p>
 */
public final class Json {

    /** 存盘用：带缩进，方便用户手改。 */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    /** 网络用：最紧凑。 */
    public static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private Json() {}

    /** 游戏内时间：第 N 天 HH:MM（1 tick = 1/20 秒，6000 tick 跨 6 小时 = 1 天 1/4）。 */
    public static String dayClock(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        long day = Math.floorDiv(dayTime, 24000L) + 1L;
        int hour = (int) ((6L + t / 1000L) % 24L);
        int minute = (int) ((t % 1000L) * 60L / 1000L);
        return Lang.f("fmt.clock", day, String.format(Locale.ROOT, "%02d:%02d", hour, minute));
    }

    /** 刻数转人话：秒 / 分秒。 */
    public static String ticksToHuman(int ticks) {
        int abs = Math.abs(ticks);
        int s = abs / 20;
        if (s < 60) {
            return Lang.f("fmt.sec", s);
        }
        return Lang.f("fmt.minsec", s / 60, s % 60);
    }
}
