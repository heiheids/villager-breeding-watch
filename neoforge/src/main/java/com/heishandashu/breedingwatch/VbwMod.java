package com.heishandashu.breedingwatch;

import com.heishandashu.breedingwatch.client.VbwClient;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.net.VbwNetworking;
import com.heishandashu.breedingwatch.server.ServerHooks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 公共入口（NeoForge 侧）。
 *
 * <p>只做接线：注册网络通道、把服务端监听器挂到 game bus 上。
 * 具体功能都在各自的包里，本类不承载业务逻辑。</p>
 *
 * <p>事件总线分两条，挂错位置**不会报错、只是永远不触发**，这是 NeoForge 最典型的坑：
 * <ul>
 *   <li>mod bus（构造函数拿到的 {@code modBus}）：注册期事件，
 *       如 {@code RegisterPayloadHandlersEvent}、{@code RegisterKeyMappingsEvent}</li>
 *   <li>game bus（{@code NeoForge.EVENT_BUS}）：运行期事件，
 *       如 tick、登录、死亡、右键、渲染</li>
 * </ul></p>
 */
@Mod(VbwMod.MOD_ID)
public final class VbwMod {

    public static final String MOD_ID = "villagerbreedingwatch";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public VbwMod(IEventBus modBus) {
        Lang.init();

        // mod bus：注册期
        modBus.addListener(VbwNetworking::onRegisterPayloads);

        // game bus：运行期
        ServerHooks.register(NeoForge.EVENT_BUS);

        // 客户端引用只在客户端侧创建；专用服务端不会加载客户端类
        if (FMLEnvironment.getDist().isClient()) {
            VbwClient.register(modBus, NeoForge.EVENT_BUS);
        }

        LOG.info("[VBW] 村民繁殖观测已加载（公共侧，NeoForge，side={}）", FMLEnvironment.getDist());
    }
}
