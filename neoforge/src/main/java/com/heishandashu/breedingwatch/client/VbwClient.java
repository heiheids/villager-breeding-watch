package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.client.ui.SettingsScreen;
import com.heishandashu.breedingwatch.client.ui.StatsScreen;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.net.ConfigDirs;
import com.heishandashu.breedingwatch.net.Payloads;
import com.heishandashu.breedingwatch.net.VbwNetworking;
import com.heishandashu.breedingwatch.server.Cond;
import com.heishandashu.breedingwatch.view.Views;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 客户端入口（NeoForge 侧）。
 *
 * <p>与 Fabric 的差别：
 * <ul>
 *   <li>入口是 {@code @EventBusSubscriber(Dist.CLIENT)} 之外的显式注册
 *       （这里用显式注册，方便公共入口按侧分发）</li>
 *   <li>按键走 mod bus 的 {@code RegisterKeyMappingsEvent}</li>
 *   <li>tick 走 game bus 的 {@code ClientTickEvent.Post}</li>
 *   <li>没有 ClientPlayConnectionEvents，进出世界靠「level 引用变化」自己检测</li>
 * </ul></p>
 */
public final class VbwClient {

    public static KeyMapping keySettings;
    public static KeyMapping keyStats;
    public static KeyMapping keyToggle;

    private VbwClient() {}

    public static void register(IEventBus modBus, IEventBus gameBus) {
        ConfigDirs.set(FMLPaths.CONFIGDIR.get());
        ClientHub.SETTINGS.load();
        Lang.setDefault(ClientHub.SETTINGS.effectiveLanguage());

        // 网络接收器 + 客户端发送端
        VbwNetworking.ON_SYNC = ClientHub.DATA::accept;
        VbwNetworking.ON_UI = ClientHub::onUi;
        VbwNetworking.CLIENT_SENDER = payload -> {
            try {
                ClientPacketDistributor.sendToServer(payload);
            } catch (Throwable t) {
                com.heishandashu.breedingwatch.VbwMod.LOG.debug(
                        "[VBW] 客户端发送 {} 失败：{}", payload.type().id(), t.toString());
            }
        };

        // 注册期事件：mod bus（IModBusEvent 挂在 game bus 上会直接抛
        // "IModBusEvent events are not allowed on the common NeoForge bus"）
        modBus.addListener(VbwClient::onRegisterKeys);
        modBus.addListener(HUD::onRegisterLayers);

        // 运行期事件：game bus
        gameBus.addListener(VbwClient::onClientTick);
        gameBus.addListener(NameTags::onCanRenderNameTag);
        gameBus.addListener(BedLines::onRenderLevelStage);
        MerchantHook.register(gameBus);

        com.heishandashu.breedingwatch.VbwMod.LOG.info(
                "[VBW] 客户端就绪（NeoForge），语言={}（游戏语言={}），语言文件={}",
                ClientHub.SETTINGS.effectiveLanguage(), ClientSettings.gameLanguage(), Lang.codes());
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        // 26.3 的 KeyMapping 构造：分类是 KeyMapping.Category，不是 String
        keySettings = new KeyMapping("key.villagerbreedingwatch.settings",
                InputConstants.KEY_V, CATEGORY);
        keyStats = new KeyMapping("key.villagerbreedingwatch.stats",
                InputConstants.KEY_B, CATEGORY);
        keyToggle = new KeyMapping("key.villagerbreedingwatch.toggle",
                InputConstants.KEY_N, CATEGORY);
        event.registerCategory(CATEGORY);
        event.register(keySettings);
        event.register(keyStats);
        event.register(keyToggle);
    }

    /**
     * 按键分类。
     *
     * <p>NeoForge 的 {@code KeyMapping.Category} 同样是 record 包 Identifier，
     * 翻译键为 {@code key.category.<namespace>.<path>}；
     * 与 Fabric 支共用同一份标准语言文件即可。</p>
     */
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                    com.heishandashu.breedingwatch.VbwMod.MOD_ID, "main"));

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (keySettings.consumeClick()) {
            mc.gui.setScreen(new SettingsScreen());
        }
        while (keyStats.consumeClick()) {
            ClientHub.sendCmd("{\"c\":\"stats\"}");
            mc.gui.setScreen(new StatsScreen());
        }
        while (keyToggle.consumeClick()) {
            ClientHub.SETTINGS.enabled = !ClientHub.SETTINGS.enabled;
            ClientHub.SETTINGS.save();
            if (!ClientHub.SETTINGS.enabled) {
                // 关掉总闸：立刻清掉本地缓存，不等服务端的空快照回来
                ClientHub.DATA.clear();
            }
            ClientHub.pushPrefs();
        }
        ClientHub.tickWorldWatch(mc);
    }

    /** 头顶名牌的短标签：优先语言表，缺键时退回服务端算好的文案。 */
    static String shortTag(Views.VillagerView v) {
        if (v.ready()) {
            return Lang.t(v.pairing() != null && v.pairing().hasTarget()
                    ? "cd.tag_ready" : "cd.tag_ready_no_target");
        }
        for (Views.Condition c : v.conditions()) {
            if (!c.ok() && !c.soft()) {
                if (Cond.FOOD.equals(c.code())) {
                    int missing = Math.max(0, v.food().need() - v.food().total());
                    return Lang.f("tag.food_missing", missing);
                }
                String s = Lang.peek("short." + c.code());
                return s != null ? s : c.label();
            }
        }
        return v.shortTag();
    }
}
