package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.view.Views;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 村民 &lt;-&gt; 绑定床 的射线（NeoForge 侧）。
 *
 * <p>26.3 的 NeoForge 用 {@code RenderLevelStageEvent}（Fabric 是
 * {@code LevelRenderEvents.BEFORE_GIZMOS}）。选
 * {@code AfterTranslucentFeatures} 阶段，与 Fabric 侧行为对齐
 * （在半透明特征之后、gizmo 收集器仍有效的时机提交）。</p>
 *
 * <p>颜色沿用同一套语义：绿=正常、橙=有丢弃风险、灰=超 16 格、
 * 橙红=不可达、红=床已不存在、青色虚线=无 HOME 时指向最近空床。</p>
 */
public final class BedLines {

    private static final int GREEN = 0xFF33D95A;
    private static final int ORANGE = 0xFFF2A63C;
    private static final int RED = 0xFFF24040;
    private static final int ORANGE_RED = 0xFFF25A33;
    private static final int GRAY = 0xFF9A9A9A;
    private static final int CYAN = 0xFF40CCE6;

    private BedLines() {}

    /**
     * 26.3 的 {@code RenderLevelStageEvent} 已经**没有 {@code Stage} 枚举**，
     * 改成一个阶段一个子类（{@code AfterLevel} / {@code AfterOpaqueBlocks} /
     * {@code AfterTranslucentFeatures} …），监听哪个阶段就监听哪个子类。
     * 直接写 {@code event.getStage()} 会编译失败 —— 这是 26.3 的事件重构。
     */
    public static void onRenderLevelStage(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        ClientSettings s = ClientHub.SETTINGS;
        if (!s.wantLines()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        try {
            for (Views.VillagerView v : ClientHub.DATA.all()) {
                Views.Bed bed = v.bed();
                if (bed == null) {
                    continue;
                }
                Vec3 from = villagerAnchor(mc, v);
                BlockPos home = parsePos(bed.homePos());
                if (home != null) {
                    int color = colorFor(bed);
                    Vec3 to = new Vec3(home.getX() + 0.5, home.getY() + 0.4, home.getZ() + 0.5);
                    if (bed.dropRisk()) {
                        dashed(from, to, color);
                    } else {
                        Gizmos.line(from, to, color);
                    }
                    box(home, color);
                } else if (bed.nearestFreeBed() != null) {
                    BlockPos free = parsePos(bed.nearestFreeBed());
                    if (free != null) {
                        dashed(from, new Vec3(free.getX() + 0.5, free.getY() + 0.4, free.getZ() + 0.5), CYAN);
                    }
                }
            }
        } catch (Throwable t) {
            // 诊断功能出问题的代价必须是「这一帧没有线」，不能崩游戏
            VbwMod.LOG.warn("[VBW] 床线绘制失败：{}", t.toString());
        }
    }

    /**
     * 村民端锚点。
     *
     * <p>客户端只同步约 5 区块内的实体；远处村民 {@code level.getEntity(eid)} 为 null，
     * 这时必须用服务端快照里带的坐标，否则「远处的线永远不显示」。</p>
     */
    private static Vec3 villagerAnchor(Minecraft mc, Views.VillagerView v) {
        Entity ent = mc.level.getEntity(v.eid());
        if (ent != null && ent.isAlive()) {
            return new Vec3(ent.getX(), ent.getY() + ent.getBbHeight() * 0.75, ent.getZ());
        }
        return new Vec3(v.x(), v.y() + 1.1, v.z());
    }

    private static int colorFor(Views.Bed bed) {
        if (!bed.homeExists()) {
            return RED;
        }
        if (Boolean.FALSE.equals(bed.reachable())) {
            return ORANGE_RED;
        }
        if (bed.homeDist() > 16) {
            return GRAY;
        }
        if (bed.dropRisk() || !bed.homeOwned()) {
            return ORANGE;
        }
        return GREEN;
    }

    private static void box(BlockPos pos, int color) {
        double x1 = pos.getX() + 0.02;
        double y1 = pos.getY() + 0.02;
        double z1 = pos.getZ() + 0.02;
        double x2 = pos.getX() + 0.98;
        double y2 = pos.getY() + 0.6;
        double z2 = pos.getZ() + 0.98;
        Gizmos.cuboid(new AABB(x1, y1, z1, x2, y2, z2),
                net.minecraft.gizmos.GizmoStyle.stroke(color, 1.5F));
    }

    /** gizmo 没有 dash 参数：按长度切成小节，间隔着画。 */
    private static void dashed(Vec3 from, Vec3 to, int color) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.001) {
            return;
        }
        int pieces = Math.max(4, Math.min(80, (int) (len * 2.0)));
        for (int i = 0; i < pieces; i += 2) {
            double t0 = (double) i / pieces;
            double t1 = Math.min(1.0, (double) (i + 1) / pieces);
            Gizmos.line(from.add(d.scale(t0)), from.add(d.scale(t1)), color);
        }
    }

    public static BlockPos parsePos(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] parts = s.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
