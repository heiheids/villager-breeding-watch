/**
 * 最小复现：用 Long.MIN_VALUE 当"从未扫描"的哨兵，再算 now - last，会整数溢出。
 *
 * 这个文件是排查"一直等待服务器数据"时用来证明根因的，不是交付内容。
 * 运行： java tools/OverflowProof.java
 */
public final class OverflowProof {

    public static void main(String[] args) {
        long last = Long.MIN_VALUE;      // 代码里的哨兵值
        int gate = 10;                   // 刷新间隔（刻）

        // 取几个典型的游戏时刻
        long[] samples = {0L, 1000L, 20000L, 100000L};
        System.out.println("gate = " + gate + "  (now - last < gate 才跳过)");
        for (long now : samples) {
            long delta = now - last;
            boolean skip = delta < gate;
            System.out.printf("now=%-8d  now-last=%-22d  skip=%-5b  %s%n",
                    now, delta, skip, skip ? "<< 这一 tick 被跳过（不该跳）" : "会扫描");
        }

        System.out.println();
        System.out.println("结论：now - Long.MIN_VALUE 溢出成负数，恒小于 gate，");
        System.out.println("      所以每一 tick 都 continue，扫描永远不会发生。");

        // 正确写法：用"有没有这条记录"来表达"从未扫描"，不要参与算术
        Long boxed = null;
        boolean never = boxed == null;
        long lastOk = never ? 0L : boxed;
        boolean skipOk = !never && (20000L - lastOk < gate);
        System.out.println();
        System.out.println("修法后（never=true 时直接扫）：skip=" + skipOk + "  期望 false");
    }
}
