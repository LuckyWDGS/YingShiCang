package com.fongmi.android.tv.server;

import android.os.Looper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Server {

    private volatile PlaybackService service;
    private volatile Nano nano;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public void setService(PlaybackService service) {
        this.service = service;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "/index.html?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        String host = local ? "127.0.0.1" : LanAddress.get();
        return "http://" + (host.isEmpty() ? "127.0.0.1" : host) + ":" + Proxy.getPort();
    }

    public String getLanAddress() {
        String host = LanAddress.get();
        Nano running = nano;
        return running == null || !running.isAlive() || host.isEmpty() ? "" : "http://" + host + ":" + running.getListeningPort();
    }

    public synchronized void start() {
        if (nano != null && nano.isAlive()) return;
        for (int i = 9978; i < 9999; i++) {
            Nano candidate = new Nano(i);
            try {
                candidate.start(10000);
                nano = candidate;
                Proxy.set(i);
                break;
            } catch (Throwable e) {
                candidate.stop();
                nano = null;
            }
        }
    }

    /**
     * Plugins stream and resolve through this server (127.0.0.1); a play address requested while it is down
     * fails. The user's 3.1.4 log: after a long pause every local call was refused and the line reported
     * 此线路暂时无法连接 (2026-10-31). Restarts it when it is not listening; true when it had to.
     */
    public synchronized boolean ensureRunning() {
        Nano running = nano;
        if (running != null && running.isAlive() && (Looper.myLooper() == Looper.getMainLooper() || accepts(running.getListeningPort()))) return false;
        nano = null;
        // Released before binding so the new server keeps the port plugins already know.
        if (running != null) running.stop();
        start();
        return true;
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Its listening socket was destroyed (GuardedServerSocket). A new server takes the same port, so plugin
     * addresses and a stream still playing through 127.0.0.1 reach it again; connections the old one already
     * accepted are left to finish.
     */
    void onDead(Nano dead) {
        Task.execute(() -> {
            synchronized (this) {
                if (nano != dead) return;
                nano = null;
                start();
            }
            DiagnosticLog.write(App.get(), "local_server_restarted", "accept_failed port=" + Proxy.getPort());
        });
    }

    public synchronized void stop() {
        // Detach now: a delayed shutdown must never stop a newly started server.
        Nano previous = nano;
        service = null;
        nano = null;
        if (previous != null) Task.execute(previous::stop);
    }
}
