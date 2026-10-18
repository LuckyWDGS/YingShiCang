package com.fongmi.android.tv.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.fongmi.android.tv.App;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** A QR address must belong to a live local interface, never a VPN or cellular tunnel. */
public final class LanAddress {
    private LanAddress() {}

    public static String get() {
        try {
            ConnectivityManager manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = manager.getActiveNetwork();
            String fallback = "";
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties props = manager.getLinkProperties(network);
                if (props == null) continue;
                for (LinkAddress link : props.getLinkAddresses()) {
                    if (!usable(link.getAddress())) continue;
                    if (network.equals(active)) return link.getAddress().getHostAddress();
                    fallback = link.getAddress().getHostAddress();
                }
            }
            if (!fallback.isEmpty()) return fallback;
        } catch (RuntimeException ignored) { }
        // Hotspot interfaces are not always represented as a ConnectivityManager network.
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback() || !iface.getName().matches("(wlan|eth|ap|swlan)\\d+")) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses()))
                    if (usable(address)) return address.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static boolean usable(InetAddress address) {
        return address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress() && !address.isMulticastAddress();
    }
}
