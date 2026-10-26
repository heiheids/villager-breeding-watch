package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.view.Views;

/**
 * 条件码。
 *
 * <p>代码里只存 token（{@code FOOD}、{@code NO_BED} …），显示时才查语言表 ——
 * 这样换语言不会把历史记录翻成另一种语言，也不可能再翻回去。</p>
 *
 * <p>hard 条件参与"现在能不能繁殖"的判定；soft 只是提示（例如背包快满、多村民同床）。</p>
 */
public final class Cond {

    // ---- hard：不满足就不生
    public static final String ADULT = "ADULT";
    public static final String COOLDOWN = "COOLDOWN";
    public static final String FOOD = "FOOD";
    public static final String AWAKE = "AWAKE";
    public static final String ACTIVITY = "ACTIVITY";
    public static final String CALM = "CALM";
    public static final String RAID = "RAID";
    public static final String BED_FREE = "BED_FREE";
    public static final String BED_REACH = "BED_REACH";

    // ---- soft：只是提醒
    public static final String TARGET = "TARGET";
    public static final String WALK_BUSY = "WALK_BUSY";
    public static final String HOME_BIND = "HOME_BIND";
    public static final String BELL = "BELL";
    public static final String INV_FULL = "INV_FULL";
    public static final String BED_CONFLICT = "BED_CONFLICT";
    public static final String CANT_REACH = "CANT_REACH";

    // ---- 失败原因（推断）：原版没有"繁殖失败事件"
    public static final String TARGET_LOST = "TARGET_LOST";
    public static final String MATE_TIMEOUT = "MATE_TIMEOUT";
    public static final String SLEEPING = "SLEEPING";
    public static final String PANIC = "PANIC";
    public static final String NO_BED = "NO_BED";
    public static final String ALL_BEDS_TAKEN = "ALL_BEDS_TAKEN";
    public static final String BED_UNREACHABLE = "BED_UNREACHABLE";

    private Cond() {}

    /** 条件名：优先语言表 {@code cond.<code>}，缺键时退回原因码本身（可读、可搜索）。 */
    public static String label(String code) {
        String t = Lang.peek("cond." + code);
        return t != null ? t : code;
    }

    /** 头顶名牌用的极短名：优先 {@code short.<code>}。 */
    public static String shortLabel(String code) {
        String t = Lang.peek("short." + code);
        return t != null ? t : label(code);
    }

    public static Views.Condition ok(String code, String detail) {
        return new Views.Condition(code, label(code), detail, true, false);
    }

    public static Views.Condition bad(String code, String detail) {
        return new Views.Condition(code, label(code), detail, false, false);
    }

    public static Views.Condition soft(boolean ok, String code, String detail) {
        return new Views.Condition(code, label(code), detail, ok, true);
    }
}
