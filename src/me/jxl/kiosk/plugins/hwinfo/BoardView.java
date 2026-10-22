package me.jxl.kiosk.plugins.hwinfo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import me.jxl.kiosk.plugins.KsTheme;

/**
 * The screensaver, drawn straight onto a canvas in the Kiosk Satellite look: flat cards on the page color, Rubik,
 * the KS radii and spacing, and the KS theme's colors in light and dark unless the user picked their own. Columns
 * of panels each hold a title, a grid of readings and an optional footer. Type follows the shorter screen side, so
 * the same layout fills a wide bar display or a tablet.
 */
final class BoardView extends View {
    /** What 100% means for Text size, Value size and Label size, tuned on a 10 inch tablet. */
    private static final float TEXT = 1.05f;
    private static final float VALUE = 0.9f;
    private static final float LABEL = 1.3f;
    /** Space above a panel's title, in dp. Tighter than the card padding at the sides. */
    private static final int HEADER_TOP = 12;

    interface Logos {
        Path logo(String name);
    }

    /** The colors one frame draws with. */
    private static final class Palette {
        int background;
        int panel;
        int text;
        int muted;
        /** Panel titles and the labels under the values. */
        int header;
        int label;
        int accent;
        int line;
        int alert;
    }

    private KsTheme theme;
    private final Logos logos;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final Palette palette = new Palette();
    private final List<Box> boxes = new ArrayList<>();
    private Board board;
    private Board laidOut;
    /** One size for every value and one for every label on the screen, so a small tile and a large one read alike. */
    private float valueSize;
    private float labelSize;

    /** Where one panel and its parts sit, worked out again only when the layout or the size changes. */
    private static final class Box {
        final int panel;
        final RectF bounds = new RectF();
        final RectF grid = new RectF();
        float header;
        float footer;
        int rows;

        Box(int panel) {
            this.panel = panel;
        }
    }

    BoardView(Context context, KsTheme theme, Logos logos) {
        super(context);
        this.theme = theme;
        this.logos = logos;
        text.setFontFeatureSettings("tnum");
        setAlpha(0f);
        animate().alpha(1f).setDuration(500).start();
    }

    /** Safe from any thread. */
    void show(Board next) {
        post(() -> {
            board = next;
            if (!next.sameLayout(laidOut)) layout();
            invalidate();
        });
    }

    /** KS switched between light and dark. Main thread. */
    void setTheme(KsTheme next) {
        theme = next;
        layout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        layout();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animate().cancel();
    }

    /** One percent of the shorter side, times the Text size setting. Type and icons scale with it. */
    private float unit() {
        float scale = board == null ? 1f : board.look.scale;
        return Math.min(getWidth(), getHeight()) / 100f * TEXT * scale;
    }

    /**
     * KS's own light and dark colors, for a Theme that differs from the one KS shows: primary, surface,
     * surfaceContainer, onSurface, onSurfaceVariant, outlineVariant and error.
     */
    private static final int[] KS_LIGHT = {0xFF488284, 0xFFF5F4F2, 0xFFFFFFFF, 0xFF444444, 0xFF5D6066, 0xFFE3E0DA, 0xFFA9501F};
    private static final int[] KS_DARK = {0xFF488284, 0xFF202124, 0xFF2A2B2F, 0xFFE8EAED, 0xFFB6BABF, 0xFF3C3E43, 0xFFD97E4C};

    private void resolvePalette(Config.Look look) {
        boolean dark = "Dark".equals(look.theme) || !"Light".equals(look.theme) && theme.isDark();
        // The live KS colors whenever they are the ones asked for, so a KS that changes its palette carries over.
        if (dark == theme.isDark()) {
            palette.accent = theme.color("primary");
            palette.background = theme.color("surface");
            palette.panel = theme.color("surfaceContainer");
            palette.text = theme.color("onSurface");
            palette.muted = theme.color("onSurfaceVariant");
            palette.line = theme.color("outlineVariant");
            palette.alert = theme.color("error");
        } else {
            int[] colors = dark ? KS_DARK : KS_LIGHT;
            palette.accent = colors[0];
            palette.background = colors[1];
            palette.panel = colors[2];
            palette.text = colors[3];
            palette.muted = colors[4];
            palette.line = colors[5];
            palette.alert = colors[6];
        }
        palette.header = palette.text;
        palette.label = palette.muted;
        if (look.custom) {
            palette.background = look.background;
            palette.panel = look.surface;
            palette.text = look.text;
            palette.muted = withAlpha(look.text, 0.7f);
            palette.header = look.header;
            palette.label = look.label;
            palette.accent = look.accent;
            palette.line = withAlpha(look.text, 0.12f);
        }
    }

    private void layout() {
        boxes.clear();
        laidOut = board;
        if (board == null || getWidth() == 0 || getHeight() == 0) return;
        float u = unit();
        float gap = theme.px(theme.cardGap());
        float pad = theme.px(theme.inset());
        List<List<Box>> columns = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (int i = 0; i < board.panels.size(); i++) {
            Config.Panel panel = board.panels.get(i);
            if (columns.isEmpty() || !panel.stack) {
                columns.add(new ArrayList<>());
                weights.add(0);
            }
            Box box = new Box(i);
            box.rows = Math.max(1, (panel.tiles.size() + panel.columns - 1) / panel.columns);
            box.header = theme.px(HEADER_TOP) + 5.2f * u;
            box.footer = hasFooter(panel) ? 5.4f * u : 0;
            columns.get(columns.size() - 1).add(box);
            weights.set(weights.size() - 1, Math.max(weights.get(weights.size() - 1), panel.columns));
            boxes.add(box);
        }
        float total = 0;
        for (int weight : weights) total += weight;
        float width = getWidth() - 2 * pad - gap * (columns.size() - 1);
        float x = pad;
        for (int c = 0; c < columns.size(); c++) {
            float columnWidth = width * weights.get(c) / total;
            List<Box> column = columns.get(c);
            float fixed = 0, rows = 0;
            for (Box box : column) {
                fixed += box.header + box.footer + pad;
                rows += box.rows;
            }
            float free = Math.max(0, getHeight() - 2 * pad - gap * (column.size() - 1) - fixed);
            float y = pad;
            for (Box box : column) {
                float height = box.header + box.footer + pad + free * box.rows / rows;
                box.bounds.set(x, y, x + columnWidth, y + height);
                box.grid.set(x + pad / 2f, y + box.header, x + columnWidth - pad / 2f, y + height - box.footer - pad / 2f);
                y += height + gap;
            }
            x += columnWidth + gap;
        }
        uniformSizes(board.look);
    }

    /**
     * The label and value sizes the smallest tile allows, times Label size and Value size. A value never grows
     * past the size at which the tightest tile's value would crowd its label.
     */
    private void uniformSizes(Config.Look look) {
        float label = Float.MAX_VALUE, fill = Float.MAX_VALUE;
        for (Box box : boxes) {
            Config.Panel panel = board.panels.get(box.panel);
            float width = box.grid.width() / panel.columns;
            float height = box.grid.height() / box.rows;
            if (panel.tiles.isEmpty()) continue;
            label = Math.min(label, Math.min(height * 0.12f, width * 0.08f));
            fill = Math.min(fill, Math.min(height * 0.42f, width * 0.25f));
        }
        if (fill == Float.MAX_VALUE) {
            labelSize = valueSize = 0;
            return;
        }
        labelSize = label * LABEL * look.labelScale;
        float cap = Float.MAX_VALUE;
        for (Box box : boxes) {
            Config.Panel panel = board.panels.get(box.panel);
            float height = box.grid.height() / box.rows;
            for (Config.Tile tile : panel.tiles) cap = Math.min(cap, (height * 0.92f - labelRoom(tile)) / 0.7f);
        }
        valueSize = Math.max(0, Math.min(fill * VALUE * look.valueScale, cap));
    }

    private float labelRoom(Config.Tile tile) {
        return tile.label.isEmpty() ? 0 : labelSize * 1.7f;
    }

    private static boolean hasFooter(Config.Panel panel) {
        return !panel.footerText.isEmpty() || !panel.footerLogo.isEmpty() && !"None".equals(panel.footerLogo);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Board current = board;
        if (current == null) return;
        resolvePalette(current.look);
        canvas.drawColor(palette.background);
        if (current.panels.isEmpty()) {
            drawEmpty(canvas);
            return;
        }
        if (!current.sameLayout(laidOut)) layout();
        for (Box box : boxes) {
            // A computer that does not answer leaves its panels faded rather than gone.
            int saved = current.offline(box.panel) ? canvas.saveLayerAlpha(box.bounds, 128) : canvas.save();
            drawPanel(canvas, current, box);
            canvas.restoreToCount(saved);
        }
    }

    private void drawPanel(Canvas canvas, Board current, Box box) {
        Config.Panel panel = current.panels.get(box.panel);
        float u = unit();
        float radius = theme.px(theme.radiusCard());
        fill.setColor(palette.panel);
        canvas.drawRoundRect(box.bounds, radius, radius, fill);
        drawHeader(canvas, current, box, panel, u);
        drawTiles(canvas, current, box, panel);
        if (box.footer > 0) drawFooter(canvas, box, panel, u);
    }

    /** The icon on a soft accent tile beside the title, the way KS opens a page, and an optional reading on the right. */
    private void drawHeader(Canvas canvas, Board current, Box box, Config.Panel panel, float u) {
        RectF b = box.bounds;
        float pad = theme.px(theme.inset());
        float left = b.left + pad, right = b.right - pad;
        float tile = 5.2f * u;
        float top = b.top + theme.px(HEADER_TOP);
        float middle = top + tile / 2f;
        float x = left;
        Path icon = panel.icon.isEmpty() ? null : theme.iconPath(panel.icon);
        if (icon != null) {
            // KS's tile is 52 dp with a 14 dp radius around a 26 dp glyph. This keeps those proportions at any size.
            fill.setColor(withAlpha(palette.accent, 0.14f));
            rect.set(x, top, x + tile, top + tile);
            canvas.drawRoundRect(rect, tile * 14f / 52f, tile * 14f / 52f, fill);
            fill.setColor(palette.accent);
            drawPath(canvas, icon, x + tile / 4f, top + tile / 4f, tile / 2f, fill);
            x += tile + 1.4f * u;
        }

        float titleRight = right;
        if (!panel.headerReading.isEmpty()) {
            Board.Value extra = current.headers[box.panel];
            String value = extra == null ? "–" : (Units.number(extra.number, extra.unit, -1) + " " + extra.unit).trim();
            text.setTypeface(theme.typeface(500));
            text.setTextSize(2.3f * u);
            text.setColor(palette.text);
            float valueWidth = text.measureText(value);
            canvas.drawText(value, right - valueWidth, middle + 0.8f * u, text);
            titleRight = right - valueWidth - 1f * u;
            if (!panel.headerLabel.isEmpty()) {
                text.setTypeface(theme.typeface(400));
                text.setColor(palette.muted);
                float labelWidth = text.measureText(panel.headerLabel);
                canvas.drawText(panel.headerLabel, titleRight - labelWidth, middle + 0.8f * u, text);
                titleRight -= labelWidth + 1.6f * u;
            }
        }

        text.setTypeface(theme.typeface(600));
        text.setTextSize(2.8f * u);
        text.setColor(palette.header);
        CharSequence title = TextUtils.ellipsize(panel.title, text, Math.max(0, titleRight - x), TextUtils.TruncateAt.END);
        canvas.drawText(title, 0, title.length(), x, middle + 1f * u, text);
    }

    private void drawTiles(Canvas canvas, Board current, Box box, Config.Panel panel) {
        Board.Value[] values = current.tiles[box.panel];
        int columns = panel.columns;
        float cellWidth = box.grid.width() / columns;
        float cellHeight = box.grid.height() / box.rows;
        for (int i = 0; i < panel.tiles.size(); i++) {
            float left = box.grid.left + (i % columns) * cellWidth;
            float top = box.grid.top + (i / columns) * cellHeight;
            drawTile(canvas, current.look, panel.tiles.get(i), values[i], left, top, cellWidth, cellHeight);
        }
    }

    private void drawTile(Canvas canvas, Config.Look look, Config.Tile tile, Board.Value value, float left, float top, float width, float height) {
        boolean binary = value != null && Units.isBinary(value.unit);
        String number = value == null ? "–" : binary ? (value.number != 0 ? "Yes" : "No") : Units.number(value.number, value.unit, tile.decimals);
        String unit = value == null || !tile.unit || binary ? "" : value.unit;
        boolean alert = value != null && !Double.isNaN(tile.alertAbove) && value.number >= tile.alertAbove;

        boolean hasLabel = !tile.label.isEmpty();
        float labelRoom = labelRoom(tile);
        // The same size on every tile. Only a value too wide for its own tile shrinks below it.
        float size = valueSize;
        float unitGap = size * 0.06f;
        text.setTypeface(theme.typeface(500));
        text.setTextSize(size);
        float numberWidth = text.measureText(number);
        text.setTypeface(theme.typeface(400));
        text.setTextSize(size * 0.5f);
        float unitWidth = unit.isEmpty() ? 0 : text.measureText(unit) + unitGap;
        // Room on both sides keeps wide values in neighboring tiles apart.
        float room = width * 0.86f;
        if (numberWidth + unitWidth > room) {
            float shrink = room / (numberWidth + unitWidth);
            size *= shrink;
            numberWidth *= shrink;
            unitWidth *= shrink;
            unitGap *= shrink;
        }
        // Rubik's figures stand about 0.7 of the type size.
        float capHeight = size * 0.7f;
        float block = capHeight + labelRoom;
        float baseline = top + (height - block) / 2f + capHeight;
        float x = left + (width - numberWidth - unitWidth) / 2f;

        text.setTypeface(theme.typeface(500));
        text.setTextSize(size);
        text.setColor(value == null ? palette.muted : alert ? palette.alert : palette.text);
        canvas.drawText(number, x, baseline, text);
        if (!unit.isEmpty()) {
            text.setTypeface(theme.typeface(400));
            text.setTextSize(size * 0.5f);
            text.setColor(alert ? palette.alert : palette.muted);
            canvas.drawText(unit, x + numberWidth + unitGap, baseline, text);
        }

        if (hasLabel) {
            text.setTypeface(theme.typeface(500));
            text.setTextSize(labelSize);
            text.setColor(palette.label);
            CharSequence label = TextUtils.ellipsize(tile.label, text, width * 0.96f, TextUtils.TruncateAt.END);
            float labelWidth = text.measureText(label, 0, label.length());
            canvas.drawText(label, 0, label.length(), left + (width - labelWidth) / 2f, baseline + labelSize * 0.7f + labelSize * 0.8f, text);
        }
    }

    /** A hairline, then the logo or icon and the text, quiet like a KS row's description. */
    private void drawFooter(Canvas canvas, Box box, Config.Panel panel, float u) {
        RectF b = box.bounds;
        float pad = theme.px(theme.inset());
        float top = b.bottom - box.footer;
        fill.setColor(palette.line);
        canvas.drawRect(b.left + pad, top, b.right - pad, top + Math.max(1, theme.px(1)), fill);

        float middle = top + box.footer / 2f;
        float x = b.left + pad;
        float iconSize = 2.6f * u;
        fill.setColor(palette.muted);
        if ("Icon".equals(panel.footerLogo)) {
            Path icon = panel.footerIcon.isEmpty() ? null : theme.iconPath(panel.footerIcon);
            if (icon != null) {
                drawPath(canvas, icon, x, middle - iconSize / 2f, iconSize, fill);
                x += iconSize + 1f * u;
            }
        } else if (!panel.footerLogo.isEmpty() && !"None".equals(panel.footerLogo)) {
            Path logo = logos.logo(panel.footerLogo);
            if (logo != null) {
                // Wordmarks sit in the middle of a square, so they are fitted by the shape itself.
                logo.computeBounds(rect, true);
                if (rect.width() > 0 && rect.height() > 0) {
                    float scale = Math.min(iconSize * 0.8f / rect.height(), iconSize * 3.2f / rect.width());
                    canvas.save();
                    canvas.translate(x - rect.left * scale, middle - rect.centerY() * scale);
                    canvas.scale(scale, scale);
                    canvas.drawPath(logo, fill);
                    canvas.restore();
                    x += rect.width() * scale + 1f * u;
                }
            }
        }
        text.setTypeface(theme.typeface(500));
        text.setTextSize(2.1f * u);
        text.setColor(palette.muted);
        CharSequence label = TextUtils.ellipsize(panel.footerText, text, Math.max(0, b.right - pad - x), TextUtils.TruncateAt.END);
        canvas.drawText(label, 0, label.length(), x, middle + 0.75f * u, text);
    }

    private void drawEmpty(Canvas canvas) {
        float u = unit();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float tile = 9f * u;
        Path icon = theme.iconPath("mdi:monitor-dashboard");
        if (icon != null) {
            fill.setColor(withAlpha(palette.accent, 0.14f));
            rect.set(cx - tile / 2f, cy - 12f * u, cx + tile / 2f, cy - 12f * u + tile);
            canvas.drawRoundRect(rect, tile * 14f / 52f, tile * 14f / 52f, fill);
            fill.setColor(palette.accent);
            drawPath(canvas, icon, cx - tile / 4f, cy - 12f * u + tile / 4f, tile / 2f, fill);
        }
        text.setTypeface(theme.typeface(600));
        text.setTextSize(3.2f * u);
        text.setColor(palette.text);
        String title = "No panels yet";
        canvas.drawText(title, cx - text.measureText(title) / 2f, cy + 2.5f * u, text);
        text.setTypeface(theme.typeface(400));
        text.setTextSize(2.3f * u);
        text.setColor(palette.muted);
        String hint = "Add panels in the HWiNFO Monitor settings or press Build to start from your sensors.";
        CharSequence line = TextUtils.ellipsize(hint, text, getWidth() * 0.9f, TextUtils.TruncateAt.END);
        canvas.drawText(line, 0, line.length(), cx - text.measureText(line, 0, line.length()) / 2f, cy + 7f * u, text);
    }

    /** Draws a path from a 24 by 24 grid into a square of the given size. */
    private static void drawPath(Canvas canvas, Path path, float left, float top, float size, Paint paint) {
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(size / 24f, size / 24f);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(Color.alpha(color) * alpha) << 24) | (color & 0xFFFFFF);
    }
}
