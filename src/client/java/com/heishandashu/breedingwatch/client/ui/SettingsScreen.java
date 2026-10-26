package com.heishandashu.breedingwatch.client.ui;

import com.heishandashu.breedingwatch.client.ClientHub;
import com.heishandashu.breedingwatch.client.ClientSettings;
import com.heishandashu.breedingwatch.i18n.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 设置界面。
 *
 * <p>布局沿用 1.21.1 版本，未做任何改动：宽屏（GUI 宽度 &gt;= 420）两列并排、
 * 窄屏自动降级为单列顺排 —— 这条降级是必须的，两列在窄窗口里会让左右两列重叠，
 * 用户点到的东西和他看到的不是同一个开关（真机踩过）。</p>
 */
public final class SettingsScreen extends Screen {

    private static final String TOGGLE_MAIN = "ui.t.main";
    private static final String TOGGLE_TAG = "ui.t.tag";
    private static final String TOGGLE_LINE = "ui.t.line";
    private static final String TOGGLE_THRU = "ui.t.thru";
    private static final String TOGGLE_HUD = "ui.t.hud";
    private static final String TOGGLE_ALLLINE = "ui.t.allline";
    private static final String TOGGLE_ALLTAG = "ui.t.alltag";

    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFFCFCFCF;

    private final ClientSettings s = ClientHub.SETTINGS;

    public SettingsScreen() {
        super(Component.literal(Lang.t("ui.settings_title")));
    }

    private String langLabel() {
        String c = s.language;
        return (c == null || c.isEmpty()) ? Lang.t("ui.lang_auto") : c;
    }

    /** 依次切换：跟随游戏语言 -> 各语言文件。 */
    private void cycleLanguage() {
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add("");
        opts.addAll(Lang.codes());
        String cur = s.language == null ? "" : s.language;
        int i = opts.indexOf(cur);
        s.language = opts.get((i + 1) % opts.size());
    }

    private Button mk(String key, boolean on, int x, int y, int w, Runnable toggle) {
        return Button.builder(Component.literal((on ? "[✓] " : "[ ] ") + Lang.t(key)), b -> {
            toggle.run();
            s.save();
            ClientHub.pushPrefs();
            this.rebuildWidgets();
        }).bounds(x, y, w, 20).build();
    }

    /** 数值型设置：左 - 、中间只读显示当前值、右 +。 */
    private void mkNum(String key, int x, int y, int w, java.util.function.IntConsumer delta) {
        String text = Lang.t(key);
        int cur = key.equals("ui.radius") ? s.radius : s.interval;
        Button mid = Button.builder(Component.literal(text + " " + cur), btn -> {
        }).bounds(x + 28, y, w - 56, 20).build();
        mid.active = false;
        Button left = Button.builder(Component.literal("−"), btn -> {
            delta.accept(-1);
            s.save();
            ClientHub.pushPrefs();
            this.rebuildWidgets();
        }).bounds(x, y, 26, 20).build();
        Button right = Button.builder(Component.literal("+"), btn -> {
            delta.accept(1);
            s.save();
            ClientHub.pushPrefs();
            this.rebuildWidgets();
        }).bounds(x + w - 26, y, 26, 20).build();
        this.addRenderableWidget(left);
        this.addRenderableWidget(mid);
        this.addRenderableWidget(right);
    }

    @Override
    protected void init() {
        super.init();
        int w = 196;
        boolean twoCol = this.width >= 420;
        int lx = twoCol ? this.width / 2 - w - 4 : this.width / 2 - w / 2;
        int rx = twoCol ? this.width / 2 + 4 : lx;
        int pitch = twoCol ? 21 : 19;
        int ly = 26;

        this.addRenderableWidget(mk(TOGGLE_MAIN, s.enabled, lx, ly, w, () -> s.enabled = !s.enabled));
        ly += pitch;
        this.addRenderableWidget(mk(TOGGLE_TAG, s.nametags, lx, ly, w, () -> s.nametags = !s.nametags));
        ly += pitch;
        this.addRenderableWidget(mk(TOGGLE_LINE, s.bedLines, lx, ly, w, () -> s.bedLines = !s.bedLines));
        ly += pitch;
        this.addRenderableWidget(mk(TOGGLE_THRU, s.seeThrough, lx, ly, w, () -> s.seeThrough = !s.seeThrough));
        ly += pitch;
        this.addRenderableWidget(mk(TOGGLE_HUD, s.hud, lx, ly, w, () -> s.hud = !s.hud));

        // 窄屏（GUI 比例大 / 窗口小）时改为单列往下排，避免两组开关叠在一起点不到
        int ry = twoCol ? 26 : ly + pitch;
        this.addRenderableWidget(mk(TOGGLE_ALLLINE, s.allLines, rx, ry, w, () -> s.allLines = !s.allLines));
        ry += pitch;
        this.addRenderableWidget(mk(TOGGLE_ALLTAG, s.allTags, rx, ry, w, () -> s.allTags = !s.allTags));
        ry += pitch + 3;
        this.mkNum("ui.radius", rx, ry, w, d -> s.radius = Math.max(8, Math.min(112, s.radius + d * 8)));
        ry += pitch;
        this.mkNum("ui.interval", rx, ry, w, d -> s.interval = Math.max(2, Math.min(100, s.interval + d * 2)));
        ry += pitch + 2;
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.reload_lang")), b -> {
            Lang.reload();
            Lang.setDefault(s.effectiveLanguage());
            ClientHub.pushPrefs();
            this.rebuildWidgets();
        }).bounds(rx, ry, w, 20).build());
        ry += pitch + 2;
        this.addRenderableWidget(Button.builder(Component.literal(Lang.f("ui.lang_btn", langLabel())), b -> {
            cycleLanguage();
            s.save();
            ClientHub.pushPrefs();
            this.rebuildWidgets();
        }).bounds(rx, ry, w, 20).build());

        int by = (twoCol ? Math.max(ly, ry) : ry) + pitch + 4;
        int bx = this.width / 2 - w / 2;
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.open_stats")),
                b -> Minecraft.getInstance().gui.setScreen(new StatsScreen())).bounds(bx, by, 94, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.done")),
                b -> this.onClose()).bounds(bx + 102, by, 94, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int x = 4;
        int y = this.height - 24;
        g.text(this.font, Lang.f("ui.lang_line", Lang.defaultCode(), String.join(",", Lang.codes())),
                x, y, 0xFF9FB8D8);
        y += 11;
        g.text(this.font, Lang.t("ui.legend_hint"), x, y, DIM);
        g.centeredText(this.font, this.title, this.width / 2, 10, WHITE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        s.save();
        ClientHub.pushPrefs();
        Minecraft.getInstance().gui.setScreen(null);
    }
}
