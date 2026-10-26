package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.client.ui.SettingsScreen;
import com.heishandashu.breedingwatch.client.ui.StatsScreen;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.net.Payloads;
import com.heishandashu.breedingwatch.net.VbwNetworking;
import com.heishandashu.breedingwatch.server.Cond;
import com.heishandashu.breedingwatch.view.Views;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * 客户端入口：注册按键与接收器，并把各子系统接上事件。
 *
 * <p>26.3 的坑：客户端入口点必须写进 {@code fabric.mod.json}（非 remap Loom 不会合并
 * {@code *-client.entrypoints.json}），否则按键与界面完全不生效且没有任何报错。</p>
 */
public final class VbwClient implements ClientModInitializer {

    /** 自带分类，方便在 控制->按键绑定 里找到。 */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(VbwMod.MOD_ID, "main"));

    public static KeyMapping keySettings;
    public static KeyMapping keyStats;
    public static KeyMapping keyToggle;

    @Override
    public void onInitializeClient() {
        ClientHub.SETTINGS.load();
        Lang.setDefault(ClientHub.SETTINGS.effectiveLanguage());

        keySettings = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + VbwMod.MOD_ID + ".settings", InputConstants.KEY_V, CATEGORY));
        keyStats = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + VbwMod.MOD_ID + ".stats", InputConstants.KEY_B, CATEGORY));
        keyToggle = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + VbwMod.MOD_ID + ".toggle", InputConstants.KEY_N, CATEGORY));

        VbwNetworking.ON_SYNC = ClientHub.DATA::accept;
        VbwNetworking.ON_UI = ClientHub::onUi;
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Sync.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> VbwNetworking.handleSync(payload.json())));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Ui.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> VbwNetworking.handleUi(payload.json())));

        ClientTickEvents.END_CLIENT_TICK.register(VbwClient::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientHub.onJoinedWorld());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientHub.onLeftWorld());

        HUD.register();
        BedLines.register();
        NameTags.register();
        MerchantHook.register();

        VbwMod.LOG.info("[VBW] 客户端就绪，语言={}（游戏语言={}），语言文件={}",
                ClientHub.SETTINGS.effectiveLanguage(), ClientSettings.gameLanguage(), Lang.codes());
    }

    private static void onTick(Minecraft mc) {
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

    /** 调试：把最近一次快照打到日志（只给开发者用，正常不调用）。 */
    static void dumpSnapshot() {
        Views.Snapshot s = ClientHub.DATA.snapshot();
        if (s != null) {
            VbwMod.LOG.info("[VBW] 快照转储：{}", Json.COMPACT.toJson(s));
        }
    }
}
