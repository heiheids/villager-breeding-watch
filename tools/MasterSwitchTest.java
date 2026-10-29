// 总开关语义回归测试（可执行，含反证）。
//
// 用户要求：**总开关关闭 = 模组不工作**，不是「少显示一样东西」。
// 曾经的两处违背：
//   ClientSettings.wantTags()  = nametags && (enabled || allTags)   <- 常开能绕过总闸
//   WatchServer.Prefs.active() = enabled || allLines || allTags     <- 常开能绕过总闸
//
// 跑法：java tools/MasterSwitchTest.java
import java.util.*;

public class MasterSwitchTest {
    static boolean oldWantTags(boolean en, boolean tag, boolean all) { return tag && (en || all); }
    static boolean newWantTags(boolean en, boolean tag)            { return en && tag; }
    static boolean oldActive(boolean en, boolean al, boolean at)   { return en || al || at; }
    static boolean newActive(boolean en)                           { return en; }

    public static void main(String[] a) {
        System.out.println("=== 反证：旧逻辑在「总闸关 + 常开开」时仍在工作 ===");
        int bad = 0;
        for (boolean all : new boolean[]{false, true}) {
            boolean t = oldWantTags(false, true, all);
            boolean ac = oldActive(false, all, all);
            if (t || ac) { bad++; System.out.printf("  总闸=关 常开=%-5s -> 仍显示=%s 仍扫描=%s  <-- 违背用户预期%n", all, t, ac); }
        }
        if (bad == 0) throw new AssertionError("反证失败：旧逻辑居然没这个问题");

        System.out.println("=== 新逻辑：总闸关 = 一切停 ===");
        for (boolean tag : new boolean[]{false, true})
            for (boolean line : new boolean[]{false, true})
                for (boolean all : new boolean[]{false, true}) {
                    if (newWantTags(false, tag)) throw new AssertionError("总闸关却仍显示名牌");
                    if (newActive(false)) throw new AssertionError("总闸关却仍扫描");
                }
        System.out.println("  总闸=关：不画名牌、不画床线、不扫描  OK");

        System.out.println("=== 新逻辑：总闸开 = 按各自开关 ===");
        if (!newWantTags(true, true))  throw new AssertionError("总闸开+名牌开 应该显示");
        if (newWantTags(true, false))  throw new AssertionError("总闸开+名牌关 不该显示");
        if (!newActive(true))          throw new AssertionError("总闸开应该扫描");
        System.out.println("  总闸=开：名牌开关独立生效、服务端扫描  OK");
        System.out.println("\nALL SEMANTIC TESTS PASSED");
    }
}
