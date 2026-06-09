package com.te.link.common.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * @Author: Talen
 */
public class NetUtil {

    private static int matchedIndex(String ip, String[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            String p = prefix[i];
            if ("*".equals(p)) { // *, assumed to be IP
                if (ip.startsWith("127.") ||
                        ip.startsWith("10.") ||
                        ip.startsWith("172.") ||
                        ip.startsWith("192.")) {
                    continue;
                }
                return i;
            } else {
                if (ip.startsWith(p)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static String getLocalIp(String ipPreference) {
        if (ipPreference == null) {
            ipPreference = "*>10>172>192>127";
        }
        String[] prefix = ipPreference.split("[> ]+");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String matchedIp = null;
            int matchedIdx = -1;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                //	跳过虚拟网卡
                if (ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> en = ni.getInetAddresses();
                // 	跳过虚拟网卡
                while (en.hasMoreElements()) {
                    InetAddress addr = en.nextElement();
                    if (!(addr instanceof Inet4Address)
                            || addr.isLoopbackAddress()
                            || addr.isAnyLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    int idx = matchedIndex(ip, prefix);
                    if (idx == -1) {
                        continue;
                    }
                    if (matchedIdx == -1) {
                        matchedIdx = idx;
                        matchedIp = ip;
                    } else if (matchedIdx > idx) {
                        matchedIdx = idx;
                        matchedIp = ip;
                    }
                }
            }
            if (matchedIp != null)
                return matchedIp;
            return "127.0.0.1";
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public static String getLocalIp() {
        return getLocalIp("*>10>172>192>127");
    }
}
