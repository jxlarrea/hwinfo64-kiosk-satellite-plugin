package me.jxl.kiosk.plugins.hwinfo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
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
    /** How much of a panel a chart takes, counted in rows of readings. */
    private static final float CHART_ROWS = 1.6f;
    /** The second and third chart lines. The first takes the accent color. */
    private static final int[] SERIES = {0xFFE8A33D, 0xFF9B7FE0};

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
    /** Redraws the charts as they scroll. */
    private final Runnable tick = this::invalidate;
    /** Everything but the chart lines, recorded once per board so a frame that only scrolls them stays cheap. */
    private Picture still;
    private Board stillBoard;
    /** Whether the panel being recorded is faded, for its chart lines. */
    private boolean fading;
    private final List<Lines> lines = new ArrayList<>();

    /** One chart's lines, drawn over the recorded screen every frame and moved left as time passes. */
    private static final class Lines {
        final RectF clip = new RectF();
        final List<Path> paths = new ArrayList<>();
        final List<Paint> pens = new ArrayList<>();
        Path area;
        Paint areaPaint;
        long recorded;
        long span;
        float width;
        boolean faded;
    }

    /** Where one panel and its parts sit, worked out again only when the layout or the size changes. */
    private static final class Box {
        final int panel;
        final RectF bounds = new RectF();
        final RectF grid = new RectF();
        final RectF chart = new RectF();
        float header;
        float footer;
        int rows;
        /** The panel's part of its column's free height: its rows of readings plus its chart. */
        float share;
        /**
         * The widest values seen along each side of the chart since the last layout. The chart keeps that room so
         * a new scale with wider values does not shift its lines.
         */
        final float[] marks = new float[2];

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
            still = null;
            if (!next.sameLayout(laidOut)) layout();
            // Nothing redraws while the window is hidden, such as with the screen off. It catches up once shown.
            if (getWindowVisibility() == VISIBLE) invalidate();
        });
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) invalidate();
    }

    /** KS switched between light and dark. Main thread. */
    void setTheme(KsTheme next) {
        theme = next;
        still = null;
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
        removeCallbacks(tick);
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
        still = null;
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
            boolean chart = !panel.chart.isEmpty();
            box.share = panel.tiles.isEmpty() ? (chart ? 2f : 1f) : box.rows + (chart ? CHART_ROWS : 0);
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
                rows += box.share;
            }
            float free = Math.max(0, getHeight() - 2 * pad - gap * (column.size() - 1) - fixed);
            float y = pad;
            for (Box box : column) {
                float height = box.header + box.footer + pad + free * box.share / rows;
                box.bounds.set(x, y, x + columnWidth, y + height);
                box.grid.set(x + pad / 2f, y + box.header, x + columnWidth - pad / 2f, y + height - box.footer - pad / 2f);
                box.chart.setEmpty();
                Config.Panel panel = board.panels.get(box.panel);
                if (!panel.chart.isEmpty()) {
                    // The readings keep their rows on top and the chart takes the rest, inset from the bottom like the sides.
                    float tiles = panel.tiles.isEmpty() ? 0 : box.grid.height() * box.rows / box.share;
                    box.chart.set(x + pad, box.grid.top + tiles + (tiles > 0 ? pad / 2f : 0), x + columnWidth - pad, box.grid.bottom - pad / 2f);
                    box.grid.bottom = box.grid.top + tiles;
                }
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
        if (!current.sameLayout(laidOut)) layout();
        if (still == null || stillBoard != current) record(current);
        canvas.drawPicture(still);

        // The lines move a quarter pixel at a time, every display frame for a short chart. A hidden window draws
        // nothing, which ends the loop until it shows again.
        long now = System.currentTimeMillis(), frame = Long.MAX_VALUE;
        for (Lines chart : lines) {
            int saved = chart.faded ? canvas.saveLayerAlpha(chart.clip, 128) : canvas.save();
            canvas.clipRect(chart.clip);
            canvas.translate(-(float) (now - chart.recorded) / chart.span * chart.width, 0);
            if (chart.area != null) canvas.drawPath(chart.area, chart.areaPaint);
            for (int i = 0; i < chart.paths.size(); i++) canvas.drawPath(chart.paths.get(i), chart.pens.get(i));
            canvas.restoreToCount(saved);
            frame = Math.min(frame, (long) (chart.span / Math.max(1, chart.width) / 4));
        }
        removeCallbacks(tick);
        if (frame <= 16) postInvalidateOnAnimation();
        else if (frame != Long.MAX_VALUE) postDelayed(tick, frame);
    }

    /** Records the screen without its chart lines, which [onDraw] adds each frame. */
    private void record(Board current) {
        lines.clear();
        still = new Picture();
        stillBoard = current;
        Canvas canvas = still.beginRecording(getWidth(), getHeight());
        resolvePalette(current.look);
        canvas.drawColor(palette.background);
        if (current.panels.isEmpty()) {
            drawEmpty(canvas);
        } else {
            for (Box box : boxes) {
                // A computer that does not answer leaves its panels faded rather than gone.
                fading = current.offline(box.panel);
                int saved = fading ? canvas.saveLayerAlpha(box.bounds, 128) : canvas.save();
                drawPanel(canvas, current, box);
                canvas.restoreToCount(saved);
            }
        }
        still.endRecording();
    }

    private void drawPanel(Canvas canvas, Board current, Box box) {
        Config.Panel panel = current.panels.get(box.panel);
        float u = unit();
        float radius = theme.px(theme.radiusCard());
        fill.setColor(palette.panel);
        canvas.drawRoundRect(box.bounds, radius, radius, fill);
        drawHeader(canvas, current, box, panel, u);
        drawTiles(canvas, current, box, panel);
        Board.Chart chart = current.charts[box.panel];
        if (chart != null && box.chart.height() > 0) drawChart(canvas, current.look, chart, box, u);
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

    /**
     * A legend with each line's current value, then the lines over the chart's time span on faint guides. Lines in
     * one unit share a scale. The first unit's values run down the left side and a second unit's down the right.
     */
    private void drawChart(Canvas canvas, Config.Look look, Board.Chart chart, Box box, float u) {
        RectF area = box.chart;
        float legend = 2.1f * u * look.chartScale;
        text.setTypeface(theme.typeface(500));
        text.setTextSize(legend);
        float x = area.left, baseline = area.top + legend * 1.8f;
        for (int i = 0; i < chart.series.size(); i++) {
            Board.Series series = chart.series.get(i);
            double latest = series.latest();
            String value = Double.isNaN(latest) ? "–" : Units.describe(latest, series.unit);
            // An entry that does not fit the rest of the row starts the next one.
            float entry = legend + text.measureText(series.name) + legend * 0.4f + text.measureText(value);
            if (x > area.left && x + entry > area.right) {
                x = area.left;
                baseline += legend * 1.4f;
            }
            fill.setColor(seriesColor(chart, i));
            canvas.drawCircle(x + legend * 0.35f, baseline - legend * 0.35f, legend * 0.3f, fill);
            x += legend;
            text.setColor(palette.label);
            canvas.drawText(series.name, x, baseline, text);
            x += text.measureText(series.name) + legend * 0.4f;
            text.setColor(palette.text);
            canvas.drawText(value, x, baseline, text);
            x += text.measureText(value) + legend * 1.2f;
        }

        float mark = legend * 0.85f;
        RectF plot = new RectF(area.left, baseline + legend * 2f, area.right, area.bottom - mark * 1.4f);
        if (plot.height() <= legend * 2) return;

        // One scale per unit, in the order the lines first use them.
        List<String> units = new ArrayList<>();
        int[] scaleOf = new int[chart.series.size()];
        for (int i = 0; i < chart.series.size(); i++) {
            String unit = chart.series.get(i).unit;
            if (!units.contains(unit)) units.add(unit);
            scaleOf[i] = units.indexOf(unit);
        }
        double[] lows = new double[units.size()], highs = new double[units.size()];
        java.util.Arrays.fill(lows, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(highs, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < chart.series.size(); i++) {
            double[] range = range(chart.series.get(i).values);
            if (Double.isNaN(range[0])) continue;
            lows[scaleOf[i]] = Math.min(lows[scaleOf[i]], range[0]);
            highs[scaleOf[i]] = Math.max(highs[scaleOf[i]], range[1]);
        }
        // A line with a fixed scale fixes it for every line in its unit, from the lowest minimum to the highest maximum.
        double[] fixedLows = new double[units.size()], fixedHighs = new double[units.size()];
        java.util.Arrays.fill(fixedLows, Double.NaN);
        java.util.Arrays.fill(fixedHighs, Double.NaN);
        for (int i = 0; i < chart.series.size(); i++) {
            Board.Series series = chart.series.get(i);
            if (Double.isNaN(series.min)) continue;
            int k = scaleOf[i];
            fixedLows[k] = Double.isNaN(fixedLows[k]) ? series.min : Math.min(fixedLows[k], series.min);
            fixedHighs[k] = Double.isNaN(fixedHighs[k]) ? series.max : Math.max(fixedHighs[k], series.max);
        }
        if (lows[0] == Double.POSITIVE_INFINITY && Double.isNaN(fixedLows[0])) return;

        // Round steps for the first scale, with as many as fit. A second scale takes the same count so both
        // sides mark the same guides.
        int steps = Math.max(2, Math.min(4, (int) (plot.height() / (mark * 2.6f))));
        double[][] scales = new double[units.size()][];
        scales[0] = Double.isNaN(fixedLows[0]) ? scale(lows[0], highs[0], steps, 0) : fixed(fixedLows[0], fixedHighs[0], steps);
        steps = (int) Math.round((scales[0][1] - scales[0][0]) / scales[0][2]);
        for (int k = 1; k < units.size(); k++) {
            if (!Double.isNaN(fixedLows[k])) scales[k] = new double[] {fixedLows[k], fixedHighs[k], (fixedHighs[k] - fixedLows[k]) / steps};
            else if (lows[k] != Double.POSITIVE_INFINITY) scales[k] = scale(lows[k], highs[k], steps, steps);
        }

        // The values along the sides.
        text.setTypeface(theme.typeface(400));
        text.setTextSize(mark);
        boolean right = units.size() > 1 && scales[1] != null;
        String[][] marks = new String[right ? 2 : 1][steps + 1];
        float[] widths = new float[marks.length];
        for (int side = 0; side < marks.length; side++) {
            double[] scale = scales[side];
            int places = places(scale[2]);
            // A fixed scale's step can repeat forever, such as a third of 100.
            if (places == 4) places = 2;
            for (int t = 0; t <= steps; t++) {
                String number = Units.number(scale[0] + t * scale[2], units.get(side), places);
                marks[side][t] = look.chartUnits ? Units.withUnit(number, units.get(side)) : number;
                widths[side] = Math.max(widths[side], text.measureText(marks[side][t]));
            }
        }
        float gap = mark * 1.2f;
        box.marks[0] = Math.max(box.marks[0], widths[0]);
        plot.left += box.marks[0] + gap;
        if (right) {
            box.marks[1] = Math.max(box.marks[1], widths[1]);
            plot.right -= box.marks[1] + gap;
        }

        fill.setColor(palette.line);
        float hair = Math.max(1, theme.px(1));
        for (int t = 0; t <= steps; t++) {
            float y = plot.bottom - plot.height() * t / steps;
            canvas.drawRect(plot.left, y, plot.right, y + hair, fill);
            float middle = y + mark * 0.35f;
            text.setColor(palette.muted);
            canvas.drawText(marks[0][t], plot.left - gap - text.measureText(marks[0][t]), middle, text);
            if (right) canvas.drawText(marks[1][t], plot.right + gap, middle, text);
        }

        float stroke = Math.max(theme.px(2), 0.3f * u);
        double total = Math.max(1, chart.span);
        Lines moving = new Lines();
        moving.recorded = System.currentTimeMillis();
        moving.span = Math.max(1, chart.span);
        moving.width = plot.width();
        moving.faded = fading;
        // Lines run off both ends as they scroll and past a fixed scale's edge.
        moving.clip.set(plot.left, plot.top - stroke, plot.right, plot.bottom + stroke);
        lines.add(moving);
        long start = moving.recorded - chart.lag - chart.span;
        for (int i = chart.series.size() - 1; i >= 0; i--) {
            Board.Series series = chart.series.get(i);
            double[] scale = scales[scaleOf[i]];
            if (scale == null) continue;
            double low = scale[0], high = scale[1];
            Path line = new Path();
            Path area0 = i == 0 ? new Path() : null;
            boolean drawing = false;
            float firstX = 0, lastX = 0;
            for (int s = 0; s < series.values.length; s++) {
                double value = series.values[s];
                if (Double.isNaN(value)) {
                    if (area0 != null && drawing) closeArea(area0, lastX, firstX, plot.bottom);
                    drawing = false;
                    continue;
                }
                float px = plot.left + (float) ((series.times[s] - start) / total) * plot.width();
                float py = plot.bottom - (float) ((value - low) / (high - low)) * plot.height();
                if (!drawing) {
                    line.moveTo(px, py);
                    if (area0 != null) area0.moveTo(px, py);
                    firstX = px;
                } else {
                    line.lineTo(px, py);
                    if (area0 != null) area0.lineTo(px, py);
                }
                lastX = px;
                drawing = true;
            }
            if (area0 != null) {
                if (drawing) closeArea(area0, lastX, firstX, plot.bottom);
                Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
                shade.setShader(new android.graphics.LinearGradient(0, plot.top, 0, plot.bottom,
                        withAlpha(seriesColor(chart, 0), 0.22f), withAlpha(seriesColor(chart, 0), 0f), android.graphics.Shader.TileMode.CLAMP));
                moving.area = area0;
                moving.areaPaint = shade;
            }
            Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(stroke);
            pen.setStrokeJoin(Paint.Join.ROUND);
            pen.setStrokeCap(Paint.Cap.ROUND);
            pen.setColor(seriesColor(chart, i));
            moving.paths.add(line);
            moving.pens.add(pen);
        }
    }

    /**
     * The bottom, top and step of a scale in round steps that reaches from low to high in about [steps] steps, or
     * in exactly [exact] steps unless it is 0.
     */
    static double[] scale(double low, double high, int steps, int exact) {
        double step = round((high - low) / steps);
        for (int tries = 0; tries < 8; tries++) {
            double first = Math.floor(low / step + 1e-9) * step;
            int count = exact > 0 ? exact : (int) Math.max(1, Math.ceil((high - first) / step - 1e-9));
            if (first + count * step >= high - 1e-9) return new double[] {first, first + count * step, step};
            step = round(step * 1.01);
        }
        return new double[] {low, high, (high - low) / Math.max(1, exact > 0 ? exact : steps)};
    }

    /** A scale from low to high in the most steps up to [steps] that land on round values, else in [steps] steps. */
    static double[] fixed(double low, double high, int steps) {
        for (int count = steps; count >= 2; count--) {
            double step = (high - low) / count;
            if (Math.abs(round(step) - step) <= 1e-9 * Math.max(1, step)) return new double[] {low, high, step};
        }
        return new double[] {low, high, (high - low) / steps};
    }

    /** The next step at or above [raw] from 1, 2, 2.5 and 5 times a power of ten. */
    private static double round(double raw) {
        double power = Math.pow(10, Math.floor(Math.log10(raw)));
        double fraction = raw / power;
        double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10;
        return nice * power;
    }

    /** The decimals a step needs, so 2.5 shows one and 50 none. */
    static int places(double step) {
        int places = 0;
        while (places < 4 && Math.abs(step * Math.pow(10, places) - Math.rint(step * Math.pow(10, places))) > 1e-6) places++;
        return places;
    }

    /** The lowest and highest value with a little room around them, or NaN for a line without values. */
    private static double[] range(double[] values) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (Double.isNaN(value)) continue;
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        if (low == Double.POSITIVE_INFINITY) return new double[] {Double.NaN, Double.NaN};
        // A span for a line that never moved.
        double span = high - low;
        if (span < 1e-6) span = Math.max(1, Math.abs(high) * 0.1);
        return new double[] {low - span * 0.1, high + span * 0.1};
    }

    private static void closeArea(Path area, float lastX, float firstX, float bottom) {
        area.lineTo(lastX, bottom);
        area.lineTo(firstX, bottom);
        area.close();
    }

    /** The line's own color, else the accent for the first line and the chart colors after it. */
    private int seriesColor(Board.Chart chart, int index) {
        int own = chart.series.get(index).color;
        if (own != 0) return own;
        return index == 0 ? palette.accent : SERIES[(index - 1) % SERIES.length];
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
