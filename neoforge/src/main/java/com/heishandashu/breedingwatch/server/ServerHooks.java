package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.net.Payloads;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端事件接线（NeoForge 侧）。
 *
 * <p>与 Fabric 的差别只是钩子叫什么；功能代码（{@code WatchServer}）一行没改。
 * 全部集中在这一个类里，方便与 Fabric 支对照维护。</p>
 */
public final class ServerHooks {

    private ServerHooks() {}

    public static void register(IEventBus gameBus) {
        // 扫描节拍：tick 尾部
        gameBus.addListener(ServerHooks::onServerTickPost);

        // 玩家进出世界
        gameBus.addListener(ServerHooks::onPlayerLoggedIn);
        gameBus.addListener(ServerHooks::onPlayerLoggedOut);

        // 实体加入世界（用来抓"刚出生的婴儿"）
        gameBus.addListener(ServerHooks::onEntityJoin);

        // 村民死亡
        gameBus.addListener(ServerHooks::onLivingDeath);

        // 右键村民
        gameBus.addListener(ServerHooks::onEntityInteract);

        // 停服存盘
        gameBus.addListener(ServerHooks::onServerStopping);
    }

    /** tick 尾部：交给与 Fabric 支共用的 {@link WatchServer#tick}。 */
    private static void onServerTickPost(ServerTickEvent.Post event) {
        try {
            WatchServer.tick(event.getServer());
        } catch (Throwable t) {
            // 诊断类功能出问题的最大代价必须是「这一轮没有输出」，不能把服务器带走
            VbwMod.LOG.warn("[VBW] tick 处理失败：{}", t.toString());
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            WatchServer.onPlayerJoin(sp);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WatchServer.onPlayerLeave(event.getEntity().getUUID());
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        // loadedFromDisk() 为真表示是从存档读回来的，不是新出生
        if (event.loadedFromDisk()) {
            return;
        }
        if (event.getEntity() instanceof Villager v && event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
            WatchServer.onEntityJoinServer(v, sl);
        }
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Villager v) {
            WatchServer.onVillagerDeath(v);
        }
    }

    /**
     * 右键村民：有交易的村民由客户端在交易界面里放按钮，没有的直接推详情。
     *
     * <p>**不取消事件**，原版行为（转头 / 打开交易）完全保留。
     * NeoForge 里"不拦截"就是在处理器里什么都不设置。</p>
     */
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (!(event.getTarget() instanceof Villager v)) {
            return;
        }
        // 总闸关闭、或玩家关掉了「右键打开村民信息」：完全交给原版，不弹任何界面
        if (!WatchServer.prefsOf(sp.getUUID()).allowRightClick()) {
            event.setCancellationResult(InteractionResult.PASS);
            return;
        }
        try {
            WatchServer.STATS.load(sp.level().getServer());
            String ui = (v.getOffers() != null && !v.getOffers().isEmpty())
                    ? "{\"c\":\"merchant\",\"eid\":" + v.getId() + "}"
                    : "{\"c\":\"detail\",\"eid\":" + v.getId() + "}";
            if (sp.connection != null && sp.connection.hasChannel(Payloads.Ui.TYPE)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new Payloads.Ui(ui));
            }
        } catch (Throwable t) {
            VbwMod.LOG.warn("[VBW] 右键村民处理失败：{}", t.toString());
        }
        // 不 setCancellationResult，也不 setCanceled：交给原版继续处理
        event.setCancellationResult(InteractionResult.PASS);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        WatchServer.saveNow();
    }
}
