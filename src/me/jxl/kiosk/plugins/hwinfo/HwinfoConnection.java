package me.jxl.kiosk.plugins.hwinfo;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Reads a HWiNFO server over a fresh TCP connection for every poll. HWiNFO answers one client at a
 * time and waits on an idle connection forever, so a kiosk that drops off the network while holding
 * one would lock out every client until HWiNFO restarts. Not thread safe: one poller owns it.
 */
final class HwinfoConnection implements Closeable {
    static final int CONNECT_TIMEOUT_MS = 2500;
    static final int READ_TIMEOUT_MS = 6000;
    static final String BUSY = "HWiNFO took the connection but did not answer. It answers one device at a time, so another one may hold it. Restart HWiNFO if this does not clear.";

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

    /** Connects, reads every sensor and closes the connection again, so HWiNFO is free for other clients. */
    HwinfoSnapshot poll() throws IOException {
        try {
            open();
            byte[] body = exchange(HwinfoProtocol.COMMAND_SENSORS);
            return HwinfoProtocol.parseSensors(body, host, version);
        } catch (SocketTimeoutException error) {
            if (socket == null || !socket.isConnected()) throw error;
            throw new IOException(BUSY, error);
        } catch (RuntimeException error) {
            throw new IOException(error.getMessage(), error);
        } finally {
            close();
        }
    }

    private void open() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        String[] info = HwinfoProtocol.parseInfo(exchange(HwinfoProtocol.COMMAND_INFO));
        host = info[0];
        version = info[1];
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
