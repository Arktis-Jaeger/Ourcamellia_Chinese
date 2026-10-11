package org.ourcamellia.core.crypto;

import org.ourcamellia.common.AeadBox;
import org.ourcamellia.common.CryptoUtil;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Hkdf;

import javax.crypto.KeyAgreement;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.XECPublicKeySpec;

/**
 * 加密群组密码学（SECURITY.md §4）：X25519 密钥协商（JCA XDH）+ HKDF-SHA256
 * （RFC 5869）+ AES-256-GCM（JCA）。服务器只中继密钥包裹与密文——它永远看不到
 * 群组密钥或明文。
 *
 * 如实披露的限制（THREAT_MODEL.md 与 UI 中均有说明）：
 * - 无“事后加密后向安全”：被移除的成员若捕获过早期密文，仍可解密旧消息；
 * - 新加入的成员无法读取入群前的历史（服务端不存储任何内容）；
 * - 服务器可以丢弃消息或剔除成员（可用性攻击），但无法伪造或读取内容。
 */
public final class GroupCrypto {

    public static final int GROUP_KEY_BYTES = 32;

    private GroupCrypto() {}

    /** 从 PKCS#8 字节重建 X25519 私钥。 */
    public static PrivateKey privateKeyFrom(byte[] pkcs8) {
        try {
            return java.security.KeyFactory.getInstance("X25519")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new Errors.Malformed("无效的 X25519 私钥");
        }
    }

    /** 从原始 32 字节编码重建 X25519 公钥。 */
    public static PublicKey publicKeyFrom(byte[] raw) {
        try {
            return java.security.KeyFactory.getInstance("X25519").generatePublic(
                    new XECPublicKeySpec(java.security.spec.NamedParameterSpec.X25519,
                            new java.math.BigInteger(1, raw)));
        } catch (GeneralSecurityException e) {
            throw new Errors.Malformed("无效的 X25519 公钥");
        }
    }

    /** 密钥对的原始 32 字节公钥。 */
    public static byte[] publicKeyBytes(java.security.KeyPair kp) {
        byte[] u = ((java.security.interfaces.XECPublicKey) kp.getPublic()).getU().toByteArray();
        // 去掉 BigInteger 符号处理可能引入的前导零字节
        if (u.length == 33 && u[0] == 0) {
            return java.util.Arrays.copyOfRange(u, 1, 33);
        }
        return u;
    }

    /** ECDH(X25519) 共享密钥。 */
    public static byte[] sharedSecret(PrivateKey priv, byte[] peerPublic) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(priv);
            ka.doPhase(publicKeyFrom(peerPublic), true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new Errors.Internal("XDH 密钥协商失败", e);
        }
    }

    // 由共享密钥推导密钥加密密钥（KEK）：绑定群组 ID 与代数——密钥轮换即换代数
    private static byte[] derive(byte[] shared, String groupId, long generation) {
        byte[] info = ("ourcamellia/group/v1/" + groupId + "/" + generation)
                .getBytes(StandardCharsets.UTF_8);
        return Hkdf.derive(shared, null, info, 32);
    }

    /** 为某一成员包裹群组密钥（新建/邀请/轮换时使用）。 */
    public static String wrapGroupKey(PrivateKey senderPriv, byte[] memberPublic,
                                      String groupId, long generation, byte[] groupKey) {
        byte[] shared = sharedSecret(senderPriv, memberPublic);
        byte[] kek = derive(shared, groupId, generation);
        byte[] box = AeadBox.seal(kek, AeadBox.aadFor("ourcamellia", "group-key", groupId, String.valueOf(generation)),
                groupKey);
        return java.util.Base64.getEncoder().encodeToString(box);
    }

    /** 解包发给我们的群组密钥块；任何失败都返回 null。 */
    public static byte[] unwrapGroupKey(PrivateKey myPriv, byte[] senderPublic,
                                        String groupId, long generation, String boxBase64) {
        try {
            byte[] box = java.util.Base64.getDecoder().decode(boxBase64);
            byte[] shared = sharedSecret(myPriv, senderPublic);
            byte[] kek = derive(shared, groupId, generation);
            return AeadBox.open(kek, AeadBox.aadFor("ourcamellia", "group-key", groupId,
                    String.valueOf(generation)), box);
        } catch (IllegalArgumentException | Errors.OcException e) {
            return null;
        }
    }

    /** 用群组密钥加密一条群组消息。 */
    public static String encryptMessage(byte[] groupKey, String groupId, byte[] plaintext) {
        byte[] box = AeadBox.seal(groupKey,
                AeadBox.aadFor("ourcamellia", "group-message", groupId), plaintext);
        return java.util.Base64.getEncoder().encodeToString(box);
    }

    /** 解密一条群组消息；认证失败返回 null。 */
    public static String decryptMessage(byte[] groupKey, String groupId, String boxBase64) {
        try {
            byte[] box = java.util.Base64.getDecoder().decode(boxBase64);
            byte[] plain = AeadBox.open(groupKey,
                    AeadBox.aadFor("ourcamellia", "group-message", groupId), box);
            return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | Errors.OcException e) {
            return null;
        }
    }

    public static byte[] randomGroupKey() {
        return CryptoUtil.randomBytes(GROUP_KEY_BYTES);
    }
}
