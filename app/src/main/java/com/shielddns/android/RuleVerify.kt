package com.shielddns.android

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 内置规则的签名校验。
 *
 * 内置策略是所有用户共享的一条线上规则源，只靠 HTTPS 不足以保证内容没被改过
 * （CDN 缓存被投毒、企业网关做 TLS 中间人、仓库写权限被盗都会让任意规则下到客户端）。
 * 所以规则文件旁边放一份 `default-rules.txt.sig`，用不受网络影响的本地公钥验签：
 *
 *   签名算法：ECDSA P-256（prime256v1）+ SHA-256，签名结果是 ASN.1 DER
 *   私钥只在发布者本地（keystore/rules-signing.pem，不入库），脚本 scripts/sign-rules.sh
 *   公钥以 base64(SPKI DER) 固定在本类里，换密钥必须发新版本
 *
 * 验签不通过就不落盘、不回退到可疑内容，继续用随包的 assets 副本（受 APK 签名保护）。
 */
object RuleVerify {

    /** base64 编码的 X.509 SubjectPublicKeyInfo（DER），ECDSA P-256 公钥。 */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEiBdpWOSMVQPh+L+2NRHPG+YPNyhklzcDTe5nrB5HxAQN" +
            "4pE8fkXbxEO26qc+wECtzJTq/cBpB9EWXYvGDX/0IA=="

    private const val ALGORITHM = "SHA256withECDSA"

    @Volatile
    private var cached: PublicKey? = null

    private fun publicKey(): PublicKey? {
        cached?.let { return it }
        val key = runCatching {
            val bytes = Base64.getDecoder().decode(PUBLIC_KEY_B64)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()
        cached = key
        return key
    }

    /**
     * 校验规则正文与签名。任一参数为空、公钥解析失败、签名格式不对都返回 false。
     * 不做任何“宽松兜底”——验不过就是不可信。
     */
    fun verify(text: ByteArray, signature: ByteArray): Boolean {
        if (text.isEmpty() || signature.isEmpty()) return false
        val key = publicKey() ?: return false
        return runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(text)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
