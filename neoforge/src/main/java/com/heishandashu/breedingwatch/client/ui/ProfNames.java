package com.heishandashu.breedingwatch.client.ui;

import com.heishandashu.breedingwatch.i18n.Lang;

/**
 * 英文 id -> 显示名。
 *
 * <p>优先查语言表（{@code prof.<id>} / {@code act.<id>}）；缺键时退回 id 本身，
 * 不再内置一份中文兜底表 —— 兜底写死中文会让英文界面里冒出中文，比显示 id 更糟。</p>
 */
public final class ProfNames {

    private ProfNames() {}

    public static String profession(String id) {
        if (id == null || id.isEmpty()) {
            return Lang.t("prof.none");
        }
        String t = Lang.peek("prof." + id);
        return t != null ? t : id;
    }

    public static String activity(String id) {
        if (id == null || id.isEmpty()) {
            return Lang.t("act.none");
        }
        String t = Lang.peek("act." + id);
        return t != null ? t : id;
    }
}
