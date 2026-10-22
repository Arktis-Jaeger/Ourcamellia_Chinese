package org.ourcamellia.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机局域网地址探测：只用于界面标题“本地@&lt;本机IP&gt;”。
 *
 * <p>规则：
 * <ol>
 *   <li>枚举所有已启用、非回环的网络接口；</li>
 *   <li>优先选择站点本地（site-local，常见为 192.168.x.x / 10.x.x.x）的 IPv4 地址；</li>
 *   <li>没有站点本地地址时，选择任意非回环 IPv4；</li>
 *   <li>仍然没有时返回“无法确定本机地址”——绝不编造一个 IP。</li>
 * </ol>
 * 多网卡时按接口名排序取第一个，结果是稳定可预测的；界面不声称它一定是
 * 对方可达的地址（发现与连接仍以实际网络数据为准）。
 */
final class LocalNet {

    private LocalNet() {}

    static String localAddress() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        candidates.add(new Candidate(ni.getName(), addr.getHostAddress(),
                                addr.isSiteLocalAddress()));
                    }
                }
            }
        } catch (SocketException e) {
            return "无法确定本机地址";
        }
        if (candidates.isEmpty()) {
            return "无法确定本机地址";
        }
        candidates.sort(Comparator
                .comparing((Candidate c) -> !c.siteLocal)
                .thenComparing(c -> c.interfaceName));
        return candidates.get(0).address;
    }

    private record Candidate(String interfaceName, String address, boolean siteLocal) {}
}
