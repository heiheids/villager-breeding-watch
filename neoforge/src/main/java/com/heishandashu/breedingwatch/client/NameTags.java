package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.view.Views;
import net.minecraft.network.chat.Component;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * 村民头顶名牌（NeoForge 侧）。
 *
 * <p><b>这里是 NeoForge 支比 Fabric 支更干净的一点</b>：
 * NeoForge 26.3 有 {@code RenderNameTagEvent.CanRender}，它带
 * {@code getEntity()} / {@code getContent()} / {@code setContent()}，
 * 可以直接**替换原版名牌的内容**——
 * 于是头顶只显示 {@code #12 ♥ 可繁殖}，不会出现「原版名字 + 我们另加一行」。</p>
 *
 * <p>Fabric 侧没有等价钩子（已逐个扫描 fabric-api 全部 jar），
 * 只能用 {@code Gizmos.billboardTextOverMob} 额外画一行。
 * 这是两个加载器在同一功能上的能力差异，不是实现取舍。</p>
 *
 * <p>限制相同：都要求客户端手里有这个实体（约 5 区块内）。</p>
 */
public final class NameTags {

    private NameTags() {}

    /** 事件在渲染线程触发；只读缓存 + 写名牌内容，不碰别的状态。 */
    public static void onCanRenderNameTag(RenderNameTagEvent.CanRender event) {
        try {
            ClientSettings s = ClientHub.SETTINGS;
            if (!s.wantTags()) {
                return;
            }
            Entity ent = event.getEntity();
            if (!(ent instanceof Villager v)) {
                return;
            }
            Views.VillagerView view = ClientHub.DATA.view(v.getId());
            if (view == null) {
                return;   // 服务端还没扫到它
            }
            String text = "#" + view.seq() + " " + (view.ready() ? "♥" : "✕") + " "
                    + VbwClient.shortTag(view);
            // 替换原版名牌内容：保留原版名字的可读性，同时给出编号与状态
            String original = event.getOriginalContent().getString();
            event.setContent(Component.literal(text + " " + original));
            if (!s.seeThrough) {
                // 不强制；原版仍按可视距离淡出
                return;
            }
            // 穿墙显示：让名牌不被方块遮挡（NeoForge 侧通过 TriState 只控制可见性，
            // 真正的深度处理由原版名牌渲染负责，这里只保证不被裁掉）
            event.setCanRender(TriState.TRUE);
        } catch (Throwable t) {
            VbwMod.LOG.warn("[VBW] 名牌处理失败：{}", t.toString());
        }
    }
}
