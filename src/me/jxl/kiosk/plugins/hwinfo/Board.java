package me.jxl.kiosk.plugins.hwinfo;

import java.util.List;

/** What the screensaver shows at one moment: the panels the user laid out and their latest readings. */
final class Board {
    static final class Value {
        final double number;
        final String unit;

        Value(double number, String unit) {
            this.number = number;
            this.unit = unit;
        }
    }

    /** One line of a chart: its samples over the chart's time span, oldest first. NaN marks a gap. */
    static final class Series {
        final String name;
        final String unit;
        final long[] times;
        final double[] values;

        Series(String name, String unit, long[] times, double[] values) {
            this.name = name;
            this.unit = unit;
            this.times = times;
            this.values = values;
        }

        double latest() {
            return values.length == 0 ? Double.NaN : values[values.length - 1];
        }
    }

    static final class Chart {
        final List<Series> series;
        final long start;
        final long end;

        Chart(List<Series> series, long start, long end) {
            this.series = series;
            this.start = start;
            this.end = end;
        }
    }

    final Config.Look look;
    final List<Config.Panel> panels;
    /** Per panel, per tile. Null where the reading is missing, such as while its computer is off. */
    final Value[][] tiles;
    final Value[] headers;
    /** Per panel, null for a panel without a chart. */
    final Chart[] charts;

    Board(Config.Look look, List<Config.Panel> panels, Value[][] tiles, Value[] headers, Chart[] charts) {
        this.look = look;
        this.panels = panels;
        this.tiles = tiles;
        this.headers = headers;
        this.charts = charts;
    }

    boolean offline(int panel) {
        Value[] values = tiles[panel];
        if (values.length == 0) return false;
        for (Value value : values) if (value != null) return false;
        return true;
    }

    /** Whether the two boards draw the same panels, so only values differ. */
    boolean sameLayout(Board other) {
        return other != null && other.panels == panels && other.look == look;
    }
}
