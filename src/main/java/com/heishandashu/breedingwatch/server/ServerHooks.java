package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.net.Payloads;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 服务端事件接线。
 *
 * <p>Fabric 与 NeoForge 的差别只在"钩子叫什么"，功能代码一行没改。这里刻意全部集中在
 * 一个类里，方便与 NeoForge 版本对照维护。</p>
 */
public final class ServerHooks {

    private ServerHooks() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WatchServer::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (handler.player instanceof ServerPlayer sp) {
                WatchServer.onPlayerJoin(sp);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                WatchServer.onPlayerLeave(handler.player.getUUID()));

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) ->
                WatchServer.onEntityJoinServer(entity, level));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Villager v) {
                WatchServer.onVillagerDeath(v);
            }
        });

        // 右键村民：没有交易的村民直接推详情，有交易的由客户端在交易界面里放按钮。
        // 不取消事件，原版行为（转头 / 打开交易）完全保留。
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer sp) || !(entity instanceof Villager v)) {
                return InteractionResult.PASS;
            }
            try {
                WatchServer.STATS.load(sp.level().getServer());
                String ui = (v.getOffers() != null && !v.getOffers().isEmpty())
                        ? "{\"c\":\"merchant\",\"eid\":" + v.getId() + "}"
                        : "{\"c\":\"detail\",\"eid\":" + v.getId() + "}";
                sendUi(sp, ui);
            } catch (Throwable t) {
                VbwMod.LOG.warn("[VBW] 右键村民处理失败：{}", t.toString());
            }
            return InteractionResult.PASS;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> WatchServer.saveNow());
    }

    private static void sendUi(ServerPlayer sp, String json) {
        if (ServerPlayNetworking.canSend(sp, Payloads.Ui.TYPE)) {
            ServerPlayNetworking.send(sp, new Payloads.Ui(json));
        }
    }
}
