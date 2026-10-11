package org.ourcamellia.core.app;

import org.ourcamellia.common.Errors;
import org.ourcamellia.core.persist.ClientStateStore;

/**
 * 首次信任（TOFU）指纹固定策略（本地模式与服务器模式共用）：
 *
 * - 在某地址首次见到的证书指纹会被记录。
 * - 已知好友从其已知地址连接，但指纹不同 → 拒绝（可能被仿冒）。
 * - 同一指纹从新地址出现则允许（对端换了地址）并记录。
 */
final class PinGuard {

    private PinGuard() {}

    /** 返回好友此前在此地址使用过的指纹；没有则返回 null。 */
    static String expectedFingerprint(ClientStateStore store, String address) {
        for (var e : store.state().friends.entrySet()) {
            ClientStateStore.FriendRec f = e.getValue();
            if (address.equals(f.address()) && f.fingerprint() != null) {
                return f.fingerprint();
            }
        }
        return null;
    }

    /** 握手后的指纹校验 + 固定值记录。 */
    static void check(ClientStateStore store, String fingerprint, String address) {
        // 同一地址对端指纹与已固定值不符 = 疑似仿冒，拒绝连接
        for (var e : store.state().friends.entrySet()) {
            ClientStateStore.FriendRec f = e.getValue();
            if (address.equals(f.address()) && f.fingerprint() != null
                    && !f.fingerprint().equalsIgnoreCase(fingerprint)) {
                throw new Errors.Network(
                        "地址 " + address + " 的证书已变更（可能被仿冒）；连接被拒绝");
            }
        }
        // 记录指纹 → 地址 的映射（TOFU：首次见到即信任并留存）
        ClientStateStore.PinRec pin = store.pin(fingerprint);
        if (pin == null || !pin.addresses().contains(address)) {
            store.putPin(fingerprint, pin == null ? null : pin.name(), address);
        }
    }
}
