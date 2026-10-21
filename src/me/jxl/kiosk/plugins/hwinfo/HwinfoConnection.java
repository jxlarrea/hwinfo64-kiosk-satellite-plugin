package me.jxl.kiosk.plugins.hwinfo;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** One TCP connection to a HWiNFO server. Not thread safe: one poller owns it. */
final class HwinfoConnection implements Closeable {
    static final int CONNECT_TIMEOUT_MS = 2500;
    static final int READ_TIMEOUT_MS = 6000;

    private final String address;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private String host = "";
    private String version = "";

    HwinfoConnection(String address, int port) {
        this.address = address;
        this.port = port;
    }

    /** Reads every sensor, connecting first if needed. Closes the connection when anything goes wrong. */
    HwinfoSnapshot poll() throws IOException {
        try {
            if (socket == null) open();
            byte[] body = exchange(HwinfoProtocol.COMMAND_SENSORS);
            return HwinfoProtocol.parseSensors(body, host, version);
        } catch (IOException | RuntimeException error) {
            close();
            throw error instanceof IOException ? (IOException) error : new IOException(error.getMessage(), error);
        }
    }

    private void open() throws IOException {
        Socket next = new Socket();
        try {
            next.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            next.setSoTimeout(READ_TIMEOUT_MS);
            next.setTcpNoDelay(true);
            socket = next;
            in = new DataInputStream(next.getInputStream());
            out = next.getOutputStream();
            String[] info = HwinfoProtocol.parseInfo(exchange(HwinfoProtocol.COMMAND_INFO));
            host = info[0];
            version = info[1];
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    private byte[] exchange(int command) throws IOException {
        out.write(HwinfoProtocol.request(command));
        out.flush();
        byte[] header = new byte[HwinfoProtocol.HEADER_BYTES];
        in.readFully(header);
        byte[] body = new byte[HwinfoProtocol.bodyLength(header, command)];
        in.readFully(body);
        return body;
    }

    @Override
    public void close() {
        Socket current = socket;
        socket = null;
        in = null;
        out = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}
