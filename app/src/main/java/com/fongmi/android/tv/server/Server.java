package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;

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

    public synchronized void stop() {
        // Detach now: a delayed shutdown must never stop a newly started server.
        Nano previous = nano;
        service = null;
        nano = null;
        if (previous != null) Task.execute(previous::stop);
    }
}
