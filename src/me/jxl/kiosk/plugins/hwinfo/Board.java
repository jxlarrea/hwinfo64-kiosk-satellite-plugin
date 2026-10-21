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

    final Config.Look look;
    final List<Config.Panel> panels;
    /** Per panel, per tile. Null where the reading is missing, such as while its computer is off. */
    final Value[][] tiles;
    final Value[] headers;

    Board(Config.Look look, List<Config.Panel> panels, Value[][] tiles, Value[] headers) {
        this.look = look;
        this.panels = panels;
        this.tiles = tiles;
        this.headers = headers;
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
