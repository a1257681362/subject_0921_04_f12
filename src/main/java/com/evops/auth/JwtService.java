package com.evops.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准 JWT(JWS, alg=HS256) 签发与校验，零额外依赖：
 * Header {"typ":"JWT","alg":"HS256"} + Base64URL(claims JSON)，HMAC-SHA256
 * 使用 Jackson 序列化；校验时重新计算 HMAC 常量时间比对，并检查 typ/exp。
 */
@Component
public class JwtService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final AuthProperties properties;
    private final ObjectMapper objectMapper;
    private final SecretKeySpec signingKey;

    public JwtService(AuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.signingKey = new SecretKeySpec(
                properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * 签发访问令牌。
     *
     * @param userId       账号 id
     * @param username     登录名
     * @param roles        角色列表
     * @param tokenVersion 账号当前 token_version（改密自增，旧令牌 tv 不一致即失效）
     * @param sessionId    所属会话 id（访问令牌绑定会话，登出按会话立即吊销）
     * @param jti          本次访问令牌唯一编号
     */
    public String issueAccessToken(Long userId, String username, List<String> roles,
                                   int tokenVersion, Long sessionId, String jti) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getJwt().getIssuer());
        claims.put("sub", String.valueOf(userId));
        claims.put("username", username);
        claims.put("uid", userId);
        claims.put("roles", roles);
        claims.put("tv", tokenVersion);
        claims.put("sid", sessionId);
        claims.put("jti", jti);
        claims.put("type", JwtClaims.TYPE_ACCESS);
        claims.put("iat", now);
        claims.put("exp", now + properties.getJwt().getAccessTokenTtlSeconds());
        return sign(claims);
    }

    /** 解析并校验：签名（HMAC 常量时间）、类型、过期。任何不符抛 {@link JwtVerificationException}。 */
    public JwtClaims parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new JwtVerificationException("INVALID_TOKEN", "令牌格式非法");
        }
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedSig;
        byte[] providedSig;
        Map<String, Object> header;
        Map<String, Object> claims;
        try {
            expectedSig = hmac(signingInput);
            providedSig = URL_DECODER.decode(parts[2]);
            header = objectMapper.readValue(URL_DECODER.decode(parts[0]), MAP_TYPE);
            claims = objectMapper.readValue(URL_DECODER.decode(parts[1]), MAP_TYPE);
        } catch (JwtVerificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new JwtVerificationException("INVALID_TOKEN", "令牌解析失败", ex);
        }
        if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
            throw new JwtVerificationException("INVALID_TOKEN", "令牌算法或类型不匹配");
        }
        if (!constantTimeEquals(expectedSig, providedSig)) {
            throw new JwtVerificationException("INVALID_TOKEN", "令牌签名无效");
        }
        if (!JwtClaims.TYPE_ACCESS.equals(claims.get("type"))) {
            throw new JwtVerificationException("INVALID_TOKEN", "令牌类型错误");
        }
        Object expObj = claims.get("exp");
        if (!(expObj instanceof Number)
                || ((Number) expObj).longValue() <= Instant.now().getEpochSecond()) {
            // 前端拦截到该原因（HTTP 401 code=TOKEN_EXPIRED）后用刷新令牌无感换新并重试
            throw new JwtVerificationException("TOKEN_EXPIRED", "访问令牌已过期");
        }
        return toClaims(claims);
    }

    private String sign(Map<String, Object> claims) {
        try {
            Map<String, String> header = new LinkedHashMap<>();
            header.put("typ", "JWT");
            header.put("alg", "HS256");
            String h = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(header));
            String p = URL_ENCODER.encodeToString( objectMapper.writeValueAsBytes(claims));
            String signingInput = h + "." + p;
            String sig = URL_ENCODER.encodeToString(hmac(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + sig;
        } catch (Exception ex) {
            throw new IllegalStateException("签发令牌失败", ex);
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new JwtVerificationException("INVALID_TOKEN", "签名计算失败", ex);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        return java.security.MessageDigest.isEqual(a, b);
    }

    @SuppressWarnings("unchecked")
    private static JwtClaims toClaims(Map<String, Object> map) {
        JwtClaims c = new JwtClaims();
        Object uid = map.get("uid");
        c.setUserId(uid == null ? null : ((Number) uid).longValue());
        c.setUsername((String) map.get("username"));
        c.setRoles((List<String>) map.get("roles"));
        Object tv = map.get("tv");
        c.setTokenVersion(tv == null ? -1 : ((Number) tv).intValue());
        Object sid = map.get("sid");
        c.setSessionId(sid == null ? null : ((Number) sid).longValue());
        c.setJti((String) map.get("jti"));
        Object iat = map.get("iat");
        c.setIssuedAt(iat == null ? 0 : ((Number) iat).longValue());
        Object exp = map.get("exp");
        c.setExpiresAt(exp == null ? 0 : ((Number) exp).longValue());
        return c;
    }
}
