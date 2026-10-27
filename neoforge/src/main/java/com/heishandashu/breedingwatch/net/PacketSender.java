package com.heishandashu.breedingwatch.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 发送端抽象。
 *
 * <p>Fabric 与 NeoForge 的「发送一个 payload」写法不同（前者要 canSend + 静态 send，
 * 后者是 {@code PacketDistributor.sendToPlayer}）。把这一处差异抽成接口，
 * 服务端逻辑（{@code WatchServer}）就能两个加载器共用同一份代码，
 * 不必为了一行发送而维护两份 600 行的扫描器。</p>
 *
 * <p>实现方在各自的入口里注入，见 {@link VbwNetworking#SENDER}。</p>
 */
public interface PacketSender {

    /** 尽力发送：对端没装模组时静默降级，绝不抛异常。 */
    void send(ServerPlayer player, CustomPacketPayload payload);

    /** 未注入时的兜底实现（专用服务端早期阶段可能还没就绪）。 */
    PacketSender NOOP = (player, payload) -> {
        // 什么也不做：宁可让这一轮没有输出，也不能在 tick 线程里炸掉
    };
}
