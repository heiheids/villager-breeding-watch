package com.heishandashu.breedingwatch;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 公共入口（客户端与专用服务端都会执行）。
 *
 * <p>这里只做"接线"：注册网络通道、把服务端侧的监听器挂上。
 * 任何具体功能都放在各自的包里，本类不承载业务逻辑。</p>
 */
public final class VbwMod implements ModInitializer {

    public static final String MOD_ID = "villagerbreedingwatch";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        com.heishandashu.breedingwatch.i18n.Lang.init();
        com.heishandashu.breedingwatch.net.VbwNetworking.registerCommon();
        com.heishandashu.breedingwatch.server.ServerHooks.register();
        LOG.info("[VBW] 村民繁殖观测已加载（公共侧）");
    }
}
