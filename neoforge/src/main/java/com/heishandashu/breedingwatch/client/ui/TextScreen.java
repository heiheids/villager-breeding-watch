package com.heishandashu.breedingwatch.client.ui;

import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 纯文本滚动界面的基类。
 *
 * <p>26.3 的绘制模型是"描述这一帧"：不要重写 {@code render}（能编译但永远不被调用），
 * 要重写 {@link #extractRenderState(GuiGraphicsExtractor, int, int, float)}。
 * 文字用 {@code text(..)} / {@code centeredText(..)}，不再有 {@code drawString}。</p>
 */
public abstract class TextScreen extends Screen {

    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFFA0A0A0;
    static final int GREEN = 0xFF7CE38B;
    static final int RED = 0xFFF26B5A;
    static final int ORANGE = 0xFFF2A63C;
    static final int CYAN = 0xFF59C8E0;
    static final int YELLOW = 0xFFE8D24A;
    static final int DIM = 0xFFCFCFCF;

    protected int scroll;
    protected int maxScroll;
    protected List<Line> lines = new ArrayList<>();

    protected TextScreen(String title) {
        super(Component.literal(title));
    }

    /** 一行文字：内容 + 颜色。 */
    protected record Line(String text, int color) {}

    protected Line l(String text, int color) {
        return new Line(text == null ? "" : text, color);
    }

    /** 每帧重建文本行（数据可能随时更新，缓存在界面里反而会出现"刷新不生效"）。 */
    protected abstract List<Line> buildLines();

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        this.lines = buildLines();
        int top = 44;
        int bottom = this.height - 20;
        int lineH = this.font.lineHeight;
        int usable = Math.max(1, (bottom - top) / lineH);
        this.maxScroll = Math.max(0, this.lines.size() - usable);
        this.scroll = Math.max(0, Math.min(this.maxScroll, this.scroll));

        int half = Math.min(200, (this.width - 20) / 2);
        g.fill(this.width / 2 - half, top - 8, this.width / 2 + half, bottom + 2, 0x70000000);

        int y = top - 2;
        for (int i = this.scroll; i < this.lines.size() && y < bottom; i++) {
            Line line = this.lines.get(i);
            if (!line.text().isEmpty()) {
                g.text(this.font, shrink(line.text(), half * 2 - 10), this.width / 2 - half + 5, y, line.color());
            }
            y += lineH;
        }
        g.text(this.font, Lang.f("ui.scroll", this.scroll + 1,
                Math.min(this.lines.size(), this.scroll + usable), this.lines.size()),
                6, this.height - 14, GRAY);
        g.centeredText(this.font, this.title, this.width / 2, 14, WHITE);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll -= (int) Math.signum(scrollY) * 3;
        this.scroll = Math.max(0, Math.min(this.maxScroll, this.scroll));
        return true;
    }

    /** 设置/统计类界面不该冻住单人游戏。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected void setTitle(String text) {
        // Screen#title 是 final 字段，这里只能换一个"显示用"的字符串
        this.displayTitleOverride = text;
    }

    private String displayTitleOverride;

    @Override
    public net.minecraft.network.chat.Component getTitle() {
        return displayTitleOverride == null ? super.getTitle() : Component.literal(displayTitleOverride);
    }

    private String shrink(String s, int maxWidth) {
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (this.font.width(sb.toString() + s.charAt(i)) > maxWidth) {
                return sb + "…";
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** 按像素宽度折行（中文没有空格，只能逐字量）。 */
    public static List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (mc.font.width(sb.toString() + ch) > maxWidth && sb.length() > 0) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            sb.append(ch);
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    /** 顶部按钮条的通用摆放：居中的若干个按钮。 */
    protected static Button btn(String label, int x, int y, int w, Button.OnPress onPress) {
        return Button.builder(Component.literal(label), onPress).bounds(x, y, w, 20).build();
    }

    /** ✔ / ✘ 标记（避免与 Screen#tick() 同名）。 */
    public static String sym(boolean ok) {
        return ok ? "✔" : "✘";
    }

    static String jsonEsc(String raw) {
        return Json.COMPACT.toJson(raw);   // 只用来给日志做安全输出
    }
}
