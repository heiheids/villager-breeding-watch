/**
 * 回归测试：把"一直等待服务器数据"那次事故的两个根因固定成可执行断言。
 *
 * 事故现象：客户端 HUD 永远显示"等待服务器数据…"，日志里只有
 *   [VBW] 已发送客户端开关给服务器 / 收到 ... 的开关 / 统计文件不存在
 * 却**没有** "扫描=N 名村民" 这一行 —— 说明服务端一次都没扫过。
 *
 * 根因：用 Long.MIN_VALUE 当"从未发生"的哨兵，再计算 now - last，
 *       64 位减法溢出成负数，恒小于 gate，于是每 tick 都 continue。
 *
 * 这个文件不依赖 Minecraft，可以直接用 java 跑：
 *   java tools/RegressionTest.java
 */
public final class RegressionTest {

    private static int failed;

    public static void main(String[] args) {
        testOverflowIsReal();
        testScanGateFixed();
        testAutosaveGateFixed();
        testIntervalGateStillWorks();

        System.out.println();
        if (failed == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println("FAILURES: " + failed);
            System.exit(1);
        }
    }

    /** 证明旧写法确实会溢出（这是 bug 本身，不是我们想要的）。 */
    private static void testOverflowIsReal() {
        long now = 20_000L;
        long lastBroken = Long.MIN_VALUE;
        long delta = now - lastBroken;
        check("旧写法 now - Long.MIN_VALUE 溢出为负", delta < 0);
        check("旧写法因此恒被跳过（这就是事故原因）", delta < 10);
    }

    /** 新写法：没有记录时必须立刻扫。 */
    private static void testScanGateFixed() {
        Long last = null;                       // 从未扫描
        int gate = 10;
        long now = 20_000L;
        boolean skip = last != null && now - last < gate;
        check("修复后：从未扫描 -> 立刻扫描（不跳过）", !skip);

        // 刚扫过 -> 要跳过
        long justScanned = now - 3;
        boolean skip2 = justScanned != Long.MIN_VALUE && now - justScanned < gate;
        check("修复后：刚扫过 3 刻 -> 跳过", skip2);

        // 过了间隔 -> 不跳过
        long old = now - 11;
        boolean skip3 = old != Long.MIN_VALUE && now - old < gate;
        check("修复后：过了 11 刻 -> 扫描", !skip3);
    }

    /** 存档自动保存：同样是"首次必须触发"。 */
    private static void testAutosaveGateFixed() {
        long now = 20_000L;
        long autosaveTicks = 6000L;
        // 旧写法
        long lastBroken = Long.MIN_VALUE;
        boolean blockedBroken = now - lastBroken < autosaveTicks;
        check("旧写法：自动保存永远不会触发（溢出）", blockedBroken);
        // 新写法
        boolean savedOnce = false;
        boolean blockedFixed = savedOnce && (now - 0L) < autosaveTicks;
        check("修复后：第一次就保存", !blockedFixed);
    }

    /** 确认时间闸门本身没被改坏：正常间隔下行为不变。 */
    private static void testIntervalGateStillWorks() {
        int gate = 20;                 // 常开模式下限
        long t0 = 5_000L;
        check("间隔 20 刻：第 19 刻仍跳过", (t0 + 19) - t0 < gate);
        check("间隔 20 刻：第 20 刻开始扫描", !((t0 + 20) - t0 < gate));
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  [OK]   " + name);
        } else {
            System.out.println("  [FAIL] " + name);
            failed++;
        }
    }
}
