package com.evops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访问令牌过期无感刷新：访问令牌 TTL=1s，过期后业务请求收到 401 TOKEN_EXPIRED，
 * 客户端凭刷新令牌换新后用新令牌重试原请求成功——用户对令牌过期无感。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-expiry-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.auth.enabled=true",
        "evops.auth.jwt.access-token-ttl-seconds=1"
})
class AuthTokenExpiryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void expiredAccessTokenReturnsTokenExpiredThenRefreshAndRetry() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\",\"clientType\":\"WEB\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode pair = objectMapper.readTree(login.getResponse().getContentAsString()).get("data");
        String accessToken = pair.get("accessToken").asText();
        String refreshToken = pair.get("refreshToken").asText();
        assertEquals(1L, pair.get("expiresIn").asLong());

        // 未过期时正常
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 等待访问令牌过期
        Thread.sleep(1500);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        // 客户端拦截器：带刷新令牌换新（旧刷新令牌一次性失效）
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode newPair = objectMapper.readTree(refreshed.getResponse().getContentAsString()).get("data");

        // 用新访问令牌无感重试原请求成功
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + newPair.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));

        // 旧访问令牌依然只是过期态（换新不复活旧令牌）；旧刷新令牌已一次性失效
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }
}
