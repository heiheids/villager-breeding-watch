package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.view.Views;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 村民 <-> 绑定床 的射线。
 *
 * <p>26.3 把世界空间绘制的通道换成了 {@code net.minecraft.gizmos.Gizmos}：
 * built-in 的渲染回调用 {@code LevelRenderEvents.BEFORE_GIZMOS}（gizmo 收集器还开着）。
 * 老的 {@code RenderLevelStageEvent + VertexConsumer} 那条路在 26.3 已经不存在。</p>
 *
 * <p>颜色沿用 1.21.1 版本：
 * 绿=正常绑定；橙=有被丢弃的风险或不是自己的床；灰=超过 16 格；橙红=不可达；红=床已不存在；
 * 青色虚线=没有 HOME 时指向最近的空床。虚线在 gizmo 里退化为"分段画短线"。</p>
 */
public final class BedLines {

    private static final int GREEN = 0xFF33D95A;
    private static final int ORANGE = 0xFFF2A63C;
    private static final int RED = 0xFFF24040;
    private static final int ORANGE_RED = 0xFFF25A33;
    private static final int GRAY = 0xFF9A9A9A;
    private static final int CYAN = 0xFF40CCE6;

    private BedLines() {}

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> render());
    }

    private static void render() {
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
                Vec3 from = villagerAnchor(mc, v);
                if (bed == null) {
                    continue;
                }
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
            // 诊断类功能出问题的代价应该是"这一帧没有线"，不是崩游戏
            VbwMod.LOG.warn("[VBW] 床线绘制失败：{}", t.toString());
        }
    }

    /**
     * 村民端的锚点。
     *
     * <p>关键点：客户端只同步约 5 区块内的实体。远处村民 {@code level.getEntity(eid)} 为 null，
     * 这时必须用服务端快照里带的坐标 —— 否则"远处的线永远不显示"（1.21.1 版本真实踩过）。</p>
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

    /** 床位置画一个小方框，方便在方块堆里找到它。 */
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
