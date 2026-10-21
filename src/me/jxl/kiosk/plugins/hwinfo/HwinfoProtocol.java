package me.jxl.kiosk.plugins.hwinfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HWiNFO's Remote Sensor Monitoring wire format, as spoken on TCP port 27007.
 *
 * <p>The client sends a 128-byte request: {@code CRWH}, then a little-endian command. Command 1 asks for the
 * computer name and HWiNFO version, command 2 for the sensors. The server answers with a 132-byte header that
 * starts with {@code RRWH} and holds the body length at offset 12, then the body. The sensor body is
 * {@code PRWH}, the command, a length and a copy of HWiNFO's shared memory block ({@code HWiS}).
 */
final class HwinfoProtocol {
    static final int DEFAULT_PORT = 27007;
    static final int REQUEST_BYTES = 128;
    static final int HEADER_BYTES = 132;
    static final int COMMAND_INFO = 1;
    static final int COMMAND_SENSORS = 2;
    /** Real servers send about 200 KB. Anything far past that is not HWiNFO. */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HwinfoProtocol() {}

    static byte[] request(int command) {
        ByteBuffer buffer = ByteBuffer.allocate(REQUEST_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'C', 'R', 'W', 'H'}).putInt(command);
        return buffer.array();
    }

    /** Returns the body length the header announces. */
    static int bodyLength(byte[] header, int command) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (header.length < HEADER_BYTES || !magic(header, 0, "RRWH")) throw new IOException("Not a HWiNFO server");
        if (buffer.getInt(4) != command) throw new IOException("HWiNFO answered another request");
        int length = buffer.getInt(12);
        if (length < 8 || length > MAX_BODY_BYTES) throw new IOException("HWiNFO sent a reply of " + length + " bytes");
        return length;
    }

    /** The computer name and HWiNFO version from a command 1 body. */
    static String[] parseInfo(byte[] body) throws IOException {
        if (body.length < 72 || !magic(body, 0, "PRWH")) throw new IOException("Unexpected HWiNFO reply");
        return new String[] {text(body, 8, 32, LATIN1), text(body, 40, 32, LATIN1)};
    }

    static HwinfoSnapshot parseSensors(byte[] body, String host, String version) throws IOException {
        if (body.length < 12 || !magic(body, 0, "PRWH")) throw new IOException("Unexpected HWiNFO reply");
        int base = 12;
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        if (body.length < base + 48) throw new IOException("HWiNFO sent no sensors");
        if (magic(body, base, "DEAD")) throw new IOException("HWiNFO's shared memory is off");
        if (!magic(body, base, "HWiS")) throw new IOException("Unexpected HWiNFO sensor data");
        int sensorOffset = buffer.getInt(base + 20);
        int sensorSize = buffer.getInt(base + 24);
        int sensorCount = buffer.getInt(base + 28);
        int readingOffset = buffer.getInt(base + 32);
        int readingSize = buffer.getInt(base + 36);
        int readingCount = buffer.getInt(base + 40);
        int polling = body.length >= base + 48 ? buffer.getInt(base + 44) : 0;
        if (sensorSize < 264 || readingSize < 316 || sensorCount < 0 || readingCount < 0
                || !fits(body.length, base + sensorOffset, sensorSize, sensorCount)
                || !fits(body.length, base + readingOffset, readingSize, readingCount)) {
            throw new IOException("HWiNFO sensor data is cut short");
        }
        List<HwinfoSnapshot.Device> devices = new ArrayList<>(sensorCount);
        for (int i = 0; i < sensorCount; i++) {
            int at = base + sensorOffset + i * sensorSize;
            long id = buffer.getInt(at) & 0xFFFFFFFFL;
            long instance = buffer.getInt(at + 4) & 0xFFFFFFFFL;
            // Version 2 adds UTF-8 copies of the names the user may have changed.
            String name = sensorSize >= 392 ? text(body, at + 264, 128, UTF8) : "";
            if (name.isEmpty()) name = text(body, at + 136, 128, LATIN1);
            if (name.isEmpty()) name = text(body, at + 8, 128, LATIN1);
            devices.add(new HwinfoSnapshot.Device(i, id, instance, name.trim()));
        }
        List<HwinfoSnapshot.Reading> readings = new ArrayList<>(readingCount);
        for (int i = 0; i < readingCount; i++) {
            int at = base + readingOffset + i * readingSize;
            int type = buffer.getInt(at);
            int deviceIndex = buffer.getInt(at + 4);
            long readingId = buffer.getInt(at + 8) & 0xFFFFFFFFL;
            if (deviceIndex < 0 || deviceIndex >= devices.size()) continue;
            HwinfoSnapshot.Device device = devices.get(deviceIndex);
            String label = readingSize >= 460 ? text(body, at + 316, 128, UTF8) : "";
            if (label.isEmpty()) label = text(body, at + 140, 128, LATIN1);
            if (label.isEmpty()) label = text(body, at + 12, 128, LATIN1);
            String unit = readingSize >= 460 ? text(body, at + 444, 16, UTF8) : "";
            if (unit.isEmpty()) unit = text(body, at + 268, 16, LATIN1);
            String key = String.format(Locale.ROOT, "%x.%x.%x", device.id, device.instance, readingId);
            readings.add(new HwinfoSnapshot.Reading(key, type >= 0 && type < HwinfoSnapshot.TYPES.length ? HwinfoSnapshot.TYPES[type] : "other",
                    device, label.trim(), unit.trim(), buffer.getDouble(at + 284), buffer.getDouble(at + 292),
                    buffer.getDouble(at + 300), buffer.getDouble(at + 308)));
        }
        return new HwinfoSnapshot(host, version, polling, devices, readings);
    }

    private static boolean fits(int length, long offset, long size, long count) {
        return offset >= 0 && offset + size * count <= length;
    }

    private static boolean magic(byte[] data, int at, String magic) {
        if (data.length < at + 4) return false;
        for (int i = 0; i < 4; i++) if (data[at + i] != magic.charAt(i)) return false;
        return true;
    }

    private static String text(byte[] data, int at, int max, Charset charset) {
        int end = at;
        while (end < at + max && end < data.length && data[end] != 0) end++;
        return new String(data, at, end - at, charset);
    }
}
