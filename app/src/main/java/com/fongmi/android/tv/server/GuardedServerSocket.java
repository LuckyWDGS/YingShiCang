package com.fongmi.android.tv.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The local server's listening socket. When the system destroys it underneath the app (the socket is no
 * longer listening but Java does not see it closed), NanoHTTPD's accept loop fails at once on every turn and
 * spins a core at 100% while plugins get "connection refused" (user 2026-10-31: 此线路暂时无法连接 after a
 * long pause; reproduced on LDPlayer with ss -K). A few failed accepts in a row with none succeeding close
 * the socket, which ends that loop, and report the server dead so a new one is started.
 */
final class GuardedServerSocket extends ServerSocket {

    static final int FAILURES = 3;
    private static final long BACKOFF_MS = 100;

    private final Runnable onDead;
    private int failures;

    GuardedServerSocket(Runnable onDead) throws IOException {
        this.onDead = onDead;
    }

    @Override
    public Socket accept() throws IOException {
        try {
            Socket socket = super.accept();
            failures = 0;
            return socket;
        } catch (IOException error) {
            if (isClosed()) throw error;
            if (++failures >= FAILURES) {
                close();
                onDead.run();
            } else {
                try { Thread.sleep(BACKOFF_MS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            throw error;
        }
    }
}
