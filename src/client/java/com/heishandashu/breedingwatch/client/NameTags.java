package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.view.Views;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民头顶名牌：#编号 + ♥/✕ + 缺什么。
 *
 * <p><b>为什么不用"改原版名牌"</b>：26.3 没有 NeoForge 的 {@code RenderNameTagEvent}，
 * Fabric 也没有等价钩子（已核对全部 fabric-api jar）。改用 26.3 自带的世界空间文字
 * {@code Gizmos.billboardTextOverMob} —— 这正是原版生物头顶浮动文字的实现方式。</p>
 *
 * <p><b>限制如实说明</b>：这条路径要求客户端手里有这个实体。原版实体同步范围约 5 区块
 * （≈80 格），所以远处的村民头顶写不出字来；远处请看射线 / 详情页 / 统计页。
 * 这是引擎边界，不是没做。</p>
 */
public final class NameTags {

    private static final int COLOR_READY = 0xFF7CE38B;
    private static final int COLOR_BLOCKED = 0xFFF26B5A;

    private NameTags() {}

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> render());
    }

    private static void render() {
        ClientSettings s = ClientHub.SETTINGS;
        if (!s.wantTags()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        try {
            for (Views.VillagerView v : ClientHub.DATA.all()) {
                Entity ent = mc.level.getEntity(v.eid());
                if (!(ent instanceof Villager)) {
                    continue;   // 没同步到的村民画不出头顶文字（见类注释）
                }
                String text = "#" + v.seq() + " " + (v.ready() ? "♥" : "✕") + " " + VbwClient.shortTag(v);
                var props = Gizmos.billboardTextOverMob(ent, 2, text, v.ready() ? COLOR_READY : COLOR_BLOCKED, 1.0F);
                if (s.seeThrough) {
                    props.setAlwaysOnTop();
                }
            }
        } catch (Throwable t) {
            // 诊断类功能出问题的代价应该是"这一帧没有名牌"，不是崩游戏
            com.heishandashu.breedingwatch.VbwMod.LOG.warn("[VBW] 名牌绘制失败：{}", t.toString());
        }
    }

    /** 供 HUD 复用：当前生效的语言代码。 */
    static String langCode() {
        return Lang.defaultCode();
    }
}
