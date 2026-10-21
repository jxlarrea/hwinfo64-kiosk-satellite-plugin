package me.jxl.kiosk.plugins.hwinfo;

import android.graphics.Path;

/** Turns SVG path data, such as a brand logo on a 24 by 24 grid, into an Android path. */
final class SvgPath {
    private final String data;
    private int at;

    private SvgPath(String data) {
        this.data = data;
    }

    /** Returns null for data it cannot read. */
    static Path parse(String data) {
        try {
            return new SvgPath(data).read();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private Path read() {
        Path path = new Path();
        float x = 0, y = 0, startX = 0, startY = 0, controlX = 0, controlY = 0;
        char command = 0, previous = 0;
        while (true) {
            skip();
            if (at >= data.length()) break;
            char c = data.charAt(at);
            if (Character.isLetter(c)) {
                command = c;
                at++;
            } else if (command == 0) {
                throw new IllegalArgumentException("Path data must start with a command");
            } else if (command == 'M') {
                command = 'L';
            } else if (command == 'm') {
                command = 'l';
            }
            boolean relative = Character.isLowerCase(command);
            float baseX = relative ? x : 0, baseY = relative ? y : 0;
            switch (Character.toUpperCase(command)) {
                case 'M':
                    x = baseX + number();
                    y = baseY + number();
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    break;
                case 'L':
                    x = baseX + number();
                    y = baseY + number();
                    path.lineTo(x, y);
                    break;
                case 'H':
                    x = baseX + number();
                    path.lineTo(x, y);
                    break;
                case 'V':
                    y = baseY + number();
                    path.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = baseX + number(), y1 = baseY + number();
                    controlX = baseX + number();
                    controlY = baseY + number();
                    x = baseX + number();
                    y = baseY + number();
                    path.cubicTo(x1, y1, controlX, controlY, x, y);
                    break;
                }
                case 'S': {
                    boolean smooth = "CcSs".indexOf(previous) >= 0;
                    float x1 = smooth ? 2 * x - controlX : x, y1 = smooth ? 2 * y - controlY : y;
                    controlX = baseX + number();
                    controlY = baseY + number();
                    x = baseX + number();
                    y = baseY + number();
                    path.cubicTo(x1, y1, controlX, controlY, x, y);
                    break;
                }
                case 'Q':
                    controlX = baseX + number();
                    controlY = baseY + number();
                    x = baseX + number();
                    y = baseY + number();
                    path.quadTo(controlX, controlY, x, y);
                    break;
                case 'T': {
                    boolean smooth = "QqTt".indexOf(previous) >= 0;
                    controlX = smooth ? 2 * x - controlX : x;
                    controlY = smooth ? 2 * y - controlY : y;
                    x = baseX + number();
                    y = baseY + number();
                    path.quadTo(controlX, controlY, x, y);
                    break;
                }
                case 'A': {
                    float rx = number(), ry = number(), rotation = number();
                    boolean large = flag(), sweep = flag();
                    float endX = baseX + number(), endY = baseY + number();
                    arc(path, x, y, endX, endY, rx, ry, rotation, large, sweep);
                    x = endX;
                    y = endY;
                    break;
                }
                case 'Z':
                    path.close();
                    x = startX;
                    y = startY;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown path command " + command);
            }
            previous = command;
        }
        return path;
    }

    private void skip() {
        while (at < data.length()) {
            char c = data.charAt(at);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') at++;
            else break;
        }
    }

    private boolean flag() {
        skip();
        char c = data.charAt(at++);
        if (c != '0' && c != '1') throw new IllegalArgumentException("Bad arc flag");
        return c == '1';
    }

    private float number() {
        skip();
        int start = at;
        if (at < data.length() && (data.charAt(at) == '-' || data.charAt(at) == '+')) at++;
        boolean dot = false, exponent = false;
        while (at < data.length()) {
            char c = data.charAt(at);
            if (Character.isDigit(c)) {
                at++;
            } else if (c == '.' && !dot && !exponent) {
                dot = true;
                at++;
            } else if ((c == 'e' || c == 'E') && !exponent) {
                exponent = true;
                at++;
                if (at < data.length() && (data.charAt(at) == '-' || data.charAt(at) == '+')) at++;
            } else {
                break;
            }
        }
        if (start == at) throw new IllegalArgumentException("Expected a number");
        return Float.parseFloat(data.substring(start, at));
    }

    /** SVG's endpoint arc, drawn as cubic curves of at most a quarter turn each. */
    private static void arc(Path path, float x0, float y0, float x1, float y1, float rx, float ry, float degrees, boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x1, y1);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double angle = Math.toRadians(degrees), cos = Math.cos(angle), sin = Math.sin(angle);
        double dx = (x0 - x1) / 2.0, dy = (y0 - y1) / 2.0;
        double px = cos * dx + sin * dy, py = -sin * dx + cos * dy;
        double lambda = (px * px) / (rx * rx) + (py * py) / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double numerator = rx * rx * ry * ry - rx * rx * py * py - ry * ry * px * px;
        double denominator = rx * rx * py * py + ry * ry * px * px;
        double factor = Math.sqrt(Math.max(0, numerator / denominator)) * (large == sweep ? -1 : 1);
        double cx1 = factor * rx * py / ry, cy1 = -factor * ry * px / rx;
        double cx = cos * cx1 - sin * cy1 + (x0 + x1) / 2.0, cy = sin * cx1 + cos * cy1 + (y0 + y1) / 2.0;
        double start = Math.atan2((py - cy1) / ry, (px - cx1) / rx);
        double end = Math.atan2((-py - cy1) / ry, (-px - cx1) / rx);
        double delta = end - start;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        int segments = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2));
        double step = delta / segments, t = 4.0 / 3.0 * Math.tan(step / 4);
        for (int i = 0; i < segments; i++) {
            double a1 = start + i * step, a2 = a1 + step;
            double c1 = Math.cos(a1), s1 = Math.sin(a1), c2 = Math.cos(a2), s2 = Math.sin(a2);
            double ex1 = c1 - t * s1, ey1 = s1 + t * c1, ex2 = c2 + t * s2, ey2 = s2 - t * c2;
            path.cubicTo(
                    (float) (cx + rx * ex1 * cos - ry * ey1 * sin), (float) (cy + rx * ex1 * sin + ry * ey1 * cos),
                    (float) (cx + rx * ex2 * cos - ry * ey2 * sin), (float) (cy + rx * ex2 * sin + ry * ey2 * cos),
                    (float) (cx + rx * c2 * cos - ry * s2 * sin), (float) (cy + rx * c2 * sin + ry * s2 * cos));
        }
    }
}
