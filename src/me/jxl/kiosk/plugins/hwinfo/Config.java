package me.jxl.kiosk.plugins.hwinfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The plugin's settings, read once per configure into plain values. */
final class Config {
    static final class Computer {
        final String name;
        final String address;
        final int port;

        Computer(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }

        String endpoint() {
            return address.toLowerCase(Locale.ROOT) + ":" + port;
        }
    }

    /** A sensor made of several: their highest, lowest, total or average. */
    static final class Combined {
        static final String PREFIX = "combined/";

        final String name;
        final String function;
        /** True for the sensors the user picked, false for every sensor matching a name. */
        final boolean picked;
        final List<String> sensors;
        final String computer;
        final String pattern;
        final String unit;

        Combined(String name, String function, boolean picked, List<String> sensors, String computer, String pattern, String unit) {
            this.name = name;
            this.function = function;
            this.picked = picked;
            this.sensors = sensors;
            this.computer = computer;
            this.pattern = pattern;
            this.unit = unit;
        }

        /** What the pickers and the other settings store for it. */
        String id() {
            return PREFIX + name;
        }
    }

    static final class Export {
        final String reading;
        final String name;

        Export(String reading, String name) {
            this.reading = reading;
            this.name = name;
        }
    }

    static final class Tile {
        final String reading;
        final String label;
        final String statistic;
        /** -1 picks the decimals from the unit. */
        final int decimals;
        final boolean unit;
        /** NaN when the tile never turns red. */
        final double alertAbove;

        Tile(String reading, String label, String statistic, int decimals, boolean unit, double alertAbove) {
            this.reading = reading;
            this.label = label;
            this.statistic = statistic;
            this.decimals = decimals;
            this.unit = unit;
            this.alertAbove = alertAbove;
        }
    }

    /** One line of a panel's chart. */
    static final class Line {
        final String reading;
        final String label;
        /** 0 takes the line's color from the look. */
        final int color;

        Line(String reading, String label, int color) {
            this.reading = reading;
            this.label = label;
            this.color = color;
        }
    }

    static final class Panel {
        final String title;
        final String icon;
        final int columns;
        final boolean stack;
        final String headerReading;
        final String headerLabel;
        final String footerText;
        final String footerLogo;
        final String footerIcon;
        /** The sensors shown as values. */
        final List<Tile> tiles;
        /** Up to three sensors shown as chart lines, empty without a chart. */
        final List<Line> chart;
        final int chartMinutes;

        Panel(String title, String icon, int columns, boolean stack, String headerReading, String headerLabel,
                String footerText, String footerLogo, String footerIcon, List<Tile> tiles, List<Line> chart, int chartMinutes) {
            this.title = title;
            this.icon = icon;
            this.columns = columns;
            this.stack = stack;
            this.headerReading = headerReading;
            this.headerLabel = headerLabel;
            this.footerText = footerText;
            this.footerLogo = footerLogo;
            this.footerIcon = footerIcon;
            this.tiles = tiles;
            this.chart = chart;
            this.chartMinutes = chartMinutes;
        }
    }

    /** How the screensaver looks. Without custom colors it takes the KS theme's, light or dark. */
    static final class Look {
        /** Auto, Light or Dark. */
        final String theme;
        final boolean custom;
        final int accent;
        final int surface;
        final int background;
        final int text;
        final int header;
        final int label;
        /** Panel titles, header values and footers. */
        final float scale;
        /** The sensor values in the tiles. */
        final float valueScale;
        /** The labels under the sensor values. */
        final float labelScale;
        /** The chart legends and the values along the charts. */
        final float chartScale;

        Look(String theme, boolean custom, int accent, int surface, int background, int text, int header, int label, float scale, float valueScale, float labelScale,
                float chartScale) {
            this.theme = theme;
            this.custom = custom;
            this.accent = accent;
            this.surface = surface;
            this.background = background;
            this.text = text;
            this.header = header;
            this.label = label;
            this.scale = scale;
            this.valueScale = valueScale;
            this.labelScale = labelScale;
            this.chartScale = chartScale;
        }
    }

    final List<Computer> computers;
    final int intervalSeconds;
    final boolean statusTile;
    final List<Export> exports;
    final List<Combined> combined;
    final List<Panel> panels;
    final Look look;
    final boolean autoSwitch;
    final String ruleReading;
    final boolean ruleAbove;
    final double ruleValue;
    final int ruleForSeconds;
    final int ruleReleaseSeconds;
    final boolean ruleStart;

    private Config(Map<String, Object> settings) {
        List<Computer> computers = new ArrayList<>();
        for (Map<String, Object> row : rows(settings.get("computers"))) {
            String address = text(row.get("address"));
            if (address.isEmpty()) continue;
            String name = text(row.get("name"));
            int port = HwinfoProtocol.DEFAULT_PORT;
            // "192.168.1.8:27008" picks another port. A bare IPv6 address has more than one colon and keeps the default.
            int colon = address.lastIndexOf(':');
            if (colon > 0 && address.indexOf(':') == colon) {
                try {
                    port = Integer.parseInt(address.substring(colon + 1).trim());
                    address = address.substring(0, colon).trim();
                } catch (NumberFormatException ignored) {
                    // Not a port. Leave the address as typed.
                }
            }
            computers.add(new Computer(name.isEmpty() ? address : name, address, port));
        }
        this.computers = Collections.unmodifiableList(computers);
        intervalSeconds = Math.max(1, (int) number(settings.get("interval"), 2));
        statusTile = !Boolean.FALSE.equals(settings.get("statusTile"));

        List<Export> exports = new ArrayList<>();
        for (Map<String, Object> row : rows(settings.get("exports"))) {
            String reading = text(row.get("reading"));
            if (!reading.isEmpty()) exports.add(new Export(reading, text(row.get("name"))));
        }
        this.exports = Collections.unmodifiableList(exports);

        List<Combined> combined = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Map<String, Object> row : rows(settings.get("combined"))) {
            String name = text(row.get("name"));
            // Two with one name would share an ID, so the first one wins.
            if (name.isEmpty() || !names.add(name)) continue;
            List<String> sensors = new ArrayList<>();
            for (Map<String, Object> sensor : rows(row.get("sensors"))) {
                String reading = text(sensor.get("reading"));
                if (!reading.isEmpty()) sensors.add(reading);
            }
            combined.add(new Combined(name, text(row.get("function")).isEmpty() ? "Maximum" : text(row.get("function")),
                    !"Match a name".equals(row.get("source")), Collections.unmodifiableList(sensors),
                    text(row.get("computer")), text(row.get("pattern")), text(row.get("unit"))));
        }
        this.combined = Collections.unmodifiableList(combined);

        List<Panel> panels = new ArrayList<>();
        for (Map<String, Object> row : rows(settings.get("panels"))) {
            List<Tile> tiles = new ArrayList<>();
            List<Line> chart = new ArrayList<>();
            for (Map<String, Object> tile : rows(row.get("tiles"))) {
                String reading = text(tile.get("reading"));
                if (reading.isEmpty()) continue;
                String show = text(tile.get("show"));
                if (!"Chart line".equals(show)) {
                    boolean highlight = Boolean.TRUE.equals(tile.get("highlight"));
                    tiles.add(new Tile(reading, text(tile.get("label")), statistic(text(tile.get("statistic"))),
                            decimals(text(tile.get("decimals"))), !Boolean.FALSE.equals(tile.get("showUnit")),
                            highlight ? decimal(tile.get("alertAbove"), Double.NaN) : Double.NaN));
                }
                if ((show.equals("Chart line") || show.equals("Value and chart line")) && chart.size() < 3) {
                    chart.add(new Line(reading, text(tile.get("label")), Boolean.TRUE.equals(tile.get("customLine")) ? color(tile.get("lineColor"), 0) : 0));
                }
            }
            panels.add(new Panel(text(row.get("title")), text(row.get("icon")), clamp((int) number(parseOr(text(row.get("columns")), 3), 3), 1, 6),
                    Boolean.TRUE.equals(row.get("stack")), text(row.get("headerReading")), text(row.get("headerLabel")),
                    text(row.get("footerText")), text(row.get("footerLogo")), text(row.get("footerIcon")), Collections.unmodifiableList(tiles),
                    Collections.unmodifiableList(chart), clamp((int) number(parseOr(text(row.get("chartMinutes")), 5), 5), 1, 60)));
        }
        this.panels = Collections.unmodifiableList(panels);
        look = new Look(text(settings.get("theme")).isEmpty() ? "Auto" : text(settings.get("theme")), Boolean.TRUE.equals(settings.get("customColors")), color(settings.get("accentColor"), 0xFF488284),
                color(settings.get("panelColor"), 0xFF2A2B2F), color(settings.get("backgroundColor"), 0xFF202124),
                color(settings.get("textColor"), 0xFFE8EAED), color(settings.get("headerColor"), 0xFFE8EAED),
                color(settings.get("labelColor"), 0xFFB6BABF), (float) number(settings.get("textSize"), 100) / 100f,
                (float) number(settings.get("valueSize"), 100) / 100f, (float) number(settings.get("labelSize"), 100) / 100f,
                (float) number(settings.get("chartLabelSize"), 100) / 100f);

        autoSwitch = Boolean.TRUE.equals(settings.get("autoSwitch"));
        ruleReading = text(settings.get("ruleReading"));
        ruleAbove = !"Below".equals(settings.get("ruleDirection"));
        ruleValue = decimal(settings.get("ruleValue"), Double.NaN);
        ruleForSeconds = (int) number(settings.get("ruleFor"), 30);
        ruleReleaseSeconds = (int) number(settings.get("ruleRelease"), 30);
        ruleStart = Boolean.TRUE.equals(settings.get("ruleStart"));
    }

    static Config of(Map<String, Object> settings) {
        return new Config(settings);
    }

    /** Every reading this configuration shows or sends anywhere. */
    List<String> readings() {
        List<String> all = new ArrayList<>();
        for (Export export : exports) all.add(export.reading);
        for (Panel panel : panels) {
            if (!panel.headerReading.isEmpty()) all.add(panel.headerReading);
            for (Line line : panel.chart) all.add(line.reading);
            for (Tile tile : panel.tiles) all.add(tile.reading);
        }
        if (autoSwitch && !ruleReading.isEmpty()) all.add(ruleReading);
        return all;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : Collections.<Map<String, Object>>emptyList();
    }

    static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    /** A number typed into a text field. Commas work as decimal points too. */
    static double decimal(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            double parsed = Double.parseDouble(text(value).replace(',', '.'));
            return Double.isNaN(parsed) || Double.isInfinite(parsed) ? fallback : parsed;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static Object parseOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int decimals(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String statistic(String value) {
        switch (value) {
            case "Minimum": return "min";
            case "Maximum": return "max";
            case "Average": return "avg";
            default: return "value";
        }
    }

    private static int color(Object value, int fallback) {
        String text = text(value);
        if (!text.matches("#[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(text.substring(1), 16);
    }
}
