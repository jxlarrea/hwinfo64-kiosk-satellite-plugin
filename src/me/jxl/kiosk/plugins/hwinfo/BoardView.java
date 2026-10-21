package me.jxl.kiosk.plugins.hwinfo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.jxl.kiosk.plugins.KsTheme;

/**
 * The screensaver, drawn straight onto a canvas: columns of panels, each with a title, a grid of readings and an
 * optional footer. Sizes follow the shorter screen side, so the same layout fills a wide bar display or a tablet.
 */
final class BoardView extends View {
    interface Logos {
        Path logo(String name);
    }

    static final class Fonts {
        final Typeface medium;
        final Typeface semiBold;
        final Typeface bold;

        Fonts(Typeface medium, Typeface semiBold, Typeface bold) {
            this.medium = medium;
            this.semiBold = semiBold;
            this.bold = bold;
        }
    }

    private static final int TEXT = 0xFFF4F6FA;
    private static final int ALERT = 0xFFFF5D5D;

    private final KsTheme theme;
    private final Fonts fonts;
    private final Logos logos;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final List<Box> boxes = new ArrayList<>();
    private Board board;
    private Board laidOut;

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

    BoardView(Context context, KsTheme theme, Fonts fonts, Logos logos) {
        super(context);
        this.theme = theme;
        this.fonts = fonts;
        this.logos = logos;
        text.setFontFeatureSettings("tnum");
        setAlpha(0f);
        animate().alpha(1f).setDuration(700).start();
    }

    /** Safe from any thread. */
    void show(Board next) {
        post(() -> {
            board = next;
            if (!next.sameLayout(laidOut)) layout();
            invalidate();
        });
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

    private float unit() {
        float scale = board == null ? 1f : board.look.scale;
        return Math.min(getWidth(), getHeight()) / 100f * scale;
    }

    private void layout() {
        boxes.clear();
        laidOut = board;
        if (board == null || getWidth() == 0 || getHeight() == 0) return;
        float u = unit();
        float gap = 1.5f * u;
        float pad = Math.min(getWidth(), getHeight()) / 100f * 2.4f;
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
            box.header = 6.2f * u;
            box.footer = hasFooter(panel) ? 4.6f * u : 0;
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
                fixed += box.header + box.footer + 1.2f * gap;
                rows += box.rows;
            }
            float free = Math.max(0, getHeight() - 2 * pad - gap * (column.size() - 1) - fixed);
            float y = pad;
            for (Box box : column) {
                float height = box.header + box.footer + 1.2f * gap + free * box.rows / rows;
                box.bounds.set(x, y, x + columnWidth, y + height);
                float side = 1.4f * u * 0.6f;
                box.grid.set(x + side, y + box.header + 0.6f * gap, x + columnWidth - side, y + height - box.footer - 0.6f * gap);
                y += height + gap;
            }
            x += columnWidth + gap;
        }
    }

    private static boolean hasFooter(Config.Panel panel) {
        return !panel.footerText.isEmpty() || !panel.footerLogo.isEmpty() && !"None".equals(panel.footerLogo);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Board current = board;
        if (current == null) return;
        canvas.drawColor(current.look.background);
        if (current.panels.isEmpty()) {
            drawEmpty(canvas, current);
            return;
        }
        if (!current.sameLayout(laidOut)) layout();
        for (Box box : boxes) {
            boolean offline = current.offline(box.panel);
            int saved = offline ? canvas.saveLayerAlpha(box.bounds, 120) : canvas.save();
            drawPanel(canvas, current, box);
            canvas.restoreToCount(saved);
        }
    }

    private void drawPanel(Canvas canvas, Board current, Box box) {
        Config.Panel panel = current.panels.get(box.panel);
        Config.Look look = current.look;
        float u = unit();
        float radius = 1.4f * u;
        RectF b = box.bounds;

        // The card: a soft lift, a faint glow of the accent in its corner and a hairline edge.
        fill.setShader(null);
        fill.setColor(0x73000000);
        fill.setShadowLayer(3f * u, 0, 1.2f * u, 0x73000000);
        canvas.drawRoundRect(b, radius, radius, fill);
        fill.clearShadowLayer();
        // A shader still draws at the paint's alpha, so the paint goes opaque first.
        fill.setColor(Color.BLACK);
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, mix(look.surface, Color.WHITE, 0.04f), look.surface, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(b, radius, radius, fill);
        fill.setShader(new RadialGradient(b.left, b.top, Math.max(1f, b.width() * 0.9f), withAlpha(look.accent, 0.08f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(b, radius, radius, fill);
        fill.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, 0.1f * u));
        line.setColor(withAlpha(TEXT, 0.07f));
        line.setShader(null);
        canvas.drawRoundRect(b, radius, radius, line);

        drawHeader(canvas, current, box, panel, radius, u);
        drawTiles(canvas, current, box, panel);
        if (box.footer > 0) drawFooter(canvas, box, panel, radius, u);
    }

    private void drawHeader(Canvas canvas, Board current, Box box, Config.Panel panel, float radius, float u) {
        Config.Look look = current.look;
        RectF b = box.bounds;
        float left = b.left + radius * 1.1f, right = b.right - radius * 1.1f;
        float rule = b.top + box.header - 0.6f * u;
        float middle = (b.top + rule) / 2f + 0.25f * u;
        float size = 3.4f * u;
        float x = left;
        Path icon = panel.icon.isEmpty() ? null : theme.iconPath(panel.icon);
        if (icon != null) {
            fill.setColor(look.accent);
            fill.setShadowLayer(0.6f * u, 0, 0, withAlpha(look.accent, 0.45f));
            drawPath(canvas, icon, x, middle - 1.9f * u, 3.8f * u, fill);
            fill.clearShadowLayer();
            x += 3.8f * u + 1.3f * u;
        }

        // An optional reading on the right, like the coolant flow.
        float titleRight = right;
        Board.Value extra = current.headers[box.panel];
        if (!panel.headerReading.isEmpty()) {
            String value = extra == null ? "–" : (Units.number(extra.number, extra.unit, -1) + " " + extra.unit).trim();
            text.setTypeface(fonts.semiBold);
            text.setLetterSpacing(0f);
            text.setTextSize(2.4f * u);
            text.setColor(TEXT);
            float valueWidth = text.measureText(value);
            canvas.drawText(value, right - valueWidth, middle + 0.85f * u, text);
            titleRight = right - valueWidth - 1.2f * u;
            if (!panel.headerLabel.isEmpty()) {
                text.setLetterSpacing(0.06f);
                text.setTextSize(1.95f * u);
                text.setColor(withAlpha(TEXT, 0.7f));
                String label = panel.headerLabel.toUpperCase(Locale.getDefault());
                float labelWidth = text.measureText(label);
                canvas.drawText(label, titleRight - labelWidth, middle + 0.75f * u, text);
                titleRight -= labelWidth + 1.4f * u;
            }
        }

        text.setTypeface(fonts.bold);
        text.setLetterSpacing(0.02f);
        text.setTextSize(size);
        text.setColor(look.accent);
        text.setShadowLayer(0.9f * u, 0, 0, withAlpha(look.accent, 0.35f));
        CharSequence title = TextUtils.ellipsize(panel.title.toUpperCase(Locale.getDefault()), text, Math.max(0, titleRight - x), TextUtils.TruncateAt.END);
        canvas.drawText(title, 0, title.length(), x, middle + 1.2f * u, text);
        text.clearShadowLayer();

        // The accent rule under the title, fading out to the right.
        float thickness = 0.3f * u;
        line.setStyle(Paint.Style.FILL);
        line.setColor(Color.BLACK);
        line.setShader(new LinearGradient(left, 0, right, 0,
                new int[] {look.accent, withAlpha(look.accent, 0.55f), Color.TRANSPARENT}, new float[] {0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        line.setShadowLayer(1.2f * u, 0, 0, withAlpha(look.accent, 0.5f));
        rect.set(left, rule - thickness / 2, right, rule + thickness / 2);
        canvas.drawRoundRect(rect, thickness, thickness, line);
        line.clearShadowLayer();
        line.setShader(null);
    }

    private void drawTiles(Canvas canvas, Board current, Box box, Config.Panel panel) {
        Board.Value[] values = current.tiles[box.panel];
        int columns = panel.columns;
        float cellWidth = box.grid.width() / columns;
        float cellHeight = box.grid.height() / box.rows;
        for (int i = 0; i < panel.tiles.size(); i++) {
            Config.Tile tile = panel.tiles.get(i);
            float left = box.grid.left + (i % columns) * cellWidth;
            float top = box.grid.top + (i / columns) * cellHeight;
            drawTile(canvas, current.look, tile, values[i], left, top, cellWidth, cellHeight);
        }
    }

    private void drawTile(Canvas canvas, Config.Look look, Config.Tile tile, Board.Value value, float left, float top, float width, float height) {
        String number = value == null ? "–" : Units.isBinary(value.unit) ? (value.number != 0 ? "Yes" : "No") : Units.number(value.number, value.unit, tile.decimals);
        String unit = value == null || !tile.unit || Units.isBinary(value.unit) ? "" : value.unit;
        boolean alert = value != null && !Double.isNaN(tile.alertAbove) && value.number >= tile.alertAbove;

        float size = Math.min(height * 0.42f, width * 0.25f);
        text.setTypeface(fonts.medium);
        text.setLetterSpacing(-0.01f);
        text.setTextSize(size);
        float numberWidth = text.measureText(number);
        text.setTextSize(size * 0.62f);
        float unitWidth = unit.isEmpty() ? 0 : text.measureText(unit) + size * 0.04f;
        float room = width * 0.94f;
        if (numberWidth + unitWidth > room) {
            float shrink = room / (numberWidth + unitWidth);
            size *= shrink;
            numberWidth *= shrink;
            unitWidth *= shrink;
        }
        float labelSize = Math.min(height * 0.125f, width * 0.085f);
        boolean hasLabel = !tile.label.isEmpty();
        float capHeight = size * 0.7f;
        float block = capHeight + (hasLabel ? labelSize * 0.55f + labelSize : 0);
        float baseline = top + (height - block) / 2f + capHeight;
        float x = left + (width - numberWidth - unitWidth) / 2f;

        int color = value == null ? withAlpha(TEXT, 0.35f) : alert ? ALERT : TEXT;
        text.setColor(color);
        text.setTextSize(size);
        if (value != null) text.setShadowLayer(size * 0.3f, 0, 0, withAlpha(color, alert ? 0.45f : 0.16f));
        canvas.drawText(number, x, baseline, text);
        if (!unit.isEmpty()) {
            text.setTextSize(size * 0.62f);
            text.setColor(alert ? ALERT : withAlpha(TEXT, 0.82f));
            canvas.drawText(unit, x + numberWidth + size * 0.04f, baseline, text);
        }
        text.clearShadowLayer();

        if (hasLabel) {
            text.setTypeface(fonts.semiBold);
            text.setLetterSpacing(0.05f);
            text.setTextSize(labelSize);
            text.setColor(look.accent);
            CharSequence label = TextUtils.ellipsize(tile.label.toUpperCase(Locale.getDefault()), text, width * 0.98f, TextUtils.TruncateAt.END);
            float labelWidth = text.measureText(label, 0, label.length());
            canvas.drawText(label, 0, label.length(), left + (width - labelWidth) / 2f, baseline + labelSize * 0.55f + labelSize * 0.8f, text);
        }
    }

    private void drawFooter(Canvas canvas, Box box, Config.Panel panel, float radius, float u) {
        RectF b = box.bounds;
        float top = b.bottom - box.footer;
        canvas.save();
        rect.set(b);
        Path clip = new Path();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        fill.setShader(null);
        fill.setColor(withAlpha(TEXT, 0.09f));
        canvas.drawRect(b.left, top, b.right, b.bottom, fill);
        canvas.restore();
        line.setStyle(Paint.Style.FILL);
        line.setColor(withAlpha(TEXT, 0.08f));
        canvas.drawRect(b.left, top, b.right, top + Math.max(1f, 0.1f * u), line);

        float middle = top + box.footer / 2f;
        float x = b.left + radius * 1.1f;
        float iconSize = 2.7f * u;
        fill.setShader(null);
        fill.setColor(withAlpha(TEXT, 0.92f));
        if ("Icon".equals(panel.footerLogo)) {
            Path icon = panel.footerIcon.isEmpty() ? null : theme.iconPath(panel.footerIcon);
            if (icon != null) {
                drawPath(canvas, icon, x, middle - iconSize / 2f, iconSize, fill);
                x += iconSize + 1.1f * u;
            }
        } else if (!panel.footerLogo.isEmpty() && !"None".equals(panel.footerLogo)) {
            Path logo = logos.logo(panel.footerLogo);
            if (logo != null) {
                // Wordmarks sit in the middle of a square, so they are fitted by the shape itself.
                logo.computeBounds(rect, true);
                if (rect.width() > 0 && rect.height() > 0) {
                    float scale = Math.min(iconSize * 0.85f / rect.height(), iconSize * 3.2f / rect.width());
                    canvas.save();
                    canvas.translate(x - rect.left * scale, middle - rect.centerY() * scale);
                    canvas.scale(scale, scale);
                    canvas.drawPath(logo, fill);
                    canvas.restore();
                    x += rect.width() * scale + 1.1f * u;
                }
            }
        }
        text.setTypeface(fonts.semiBold);
        text.setLetterSpacing(0f);
        text.setTextSize(2.2f * u);
        text.setColor(withAlpha(TEXT, 0.92f));
        CharSequence label = TextUtils.ellipsize(panel.footerText, text, Math.max(0, b.right - radius * 1.1f - x), TextUtils.TruncateAt.END);
        canvas.drawText(label, 0, label.length(), x, middle + 0.75f * u, text);
    }

    private void drawEmpty(Canvas canvas, Board current) {
        float u = unit();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        Path icon = theme.iconPath("mdi:monitor-dashboard");
        if (icon != null) {
            fill.setColor(current.look.accent);
            drawPath(canvas, icon, cx - 4.5f * u, cy - 11f * u, 9f * u, fill);
        }
        text.setTypeface(fonts.semiBold);
        text.setLetterSpacing(0f);
        text.setTextSize(3.4f * u);
        text.setColor(TEXT);
        String title = "No panels yet";
        canvas.drawText(title, cx - text.measureText(title) / 2f, cy + 2.5f * u, text);
        text.setTypeface(fonts.medium);
        text.setTextSize(2.4f * u);
        text.setColor(withAlpha(TEXT, 0.7f));
        String hint = "Add panels in the HWiNFO Monitor settings or press Build to start from your readings.";
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

    private static int mix(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
}
