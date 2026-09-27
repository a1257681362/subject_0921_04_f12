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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录态多端会话集成测试：
 * 登录签发双令牌、无令牌/伪造 401、角色 403、刷新一次性轮换与并发只成功一次、
 * 重放吊销、登出后旧令牌立即失效、改密全部端（WEB/APP）强制下线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.auth.enabled=true"
})
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 登录并解析令牌对。 */
    private JsonNode login(String username, String password, String client) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"clientType\":\"" + client + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode loginAdmin(String client) throws Exception {
        return login("admin", "admin123", client);
    }

    private String bearer(JsonNode tokenPair) {
        return tokenPair.get("accessToken").asText();
    }

    private String refresh(JsonNode tokenPair) {
        return tokenPair.get("refreshToken").asText();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String accessToken) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    /** 管理员创建账号（带写请求三件套）。 */
    private void createUser(String adminToken, String username, String password, String roles) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"displayName\":\"" + username + "\",\"roles\":[" + roles + "]}";
        mockMvc.perform(auth(post("/api/system/users"), adminToken)
                        .header("X-Operator", "admin")
                        .header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private JsonNode doRefresh(String rawRefreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    @Test
    void loginIssuesTokenPairAndMeWorks() throws Exception {
        JsonNode pair = loginAdmin("WEB");
        assertTrue(pair.get("accessToken").asText().split("\\.").length == 3);
        assertEquals("admin", pair.get("username").asText());
        assertTrue(pair.get("roles").toString().contains("ADMIN"));
        assertEquals(1800L, pair.get("expiresIn").asLong());

        mockMvc.perform(auth(get("/api/auth/me"), bearer(pair)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));
    }

    @Test
    void wrongPasswordAndBadTokenRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-pwd\",\"clientType\":\"WEB\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));

        // 无令牌
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 伪造/篡改令牌
        JsonNode pair = loginAdmin("WEB");
        String forged = bearer(pair) + "x";
        mockMvc.perform(auth(get("/api/auth/me"), forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mockMvc.perform(auth(get("/api/auth/me"), "not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleEnforcedDispatcherForbiddenFromUserAdmin() throws Exception {
        String adminToken = bearer(loginAdmin("WEB"));
        createUser(adminToken, "dispatcher01", "disp@12345", "\"DISPATCHER\"");

        JsonNode dispatcher = login("dispatcher01", "disp@12345", "APP");
        // 调度员访问账号管理 → 403
        mockMvc.perform(auth(get("/api/system/users"), bearer(dispatcher)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 管理员可访问
        mockMvc.perform(auth(get("/api/system/users"), adminToken))
                .andExpect(status().isOk());
        // 调度员自身业务登录态正常
        mockMvc.perform(auth(get("/api/auth/me"), bearer(dispatcher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("DISPATCHER"));
    }

    @Test
    void refreshRotatesOnceOldTokenReplayRevokesSession() throws Exception {
        JsonNode pair = loginAdmin("WEB");
        String oldRefresh = refresh(pair);

        // 第一次换新成功，返回新的一对令牌
        JsonNode next = doRefresh(oldRefresh);
        String newAccess = next.get("accessToken").asText();
        String newRefresh = next.get("refreshToken").asText();
        assertTrue(!newRefresh.equals(oldRefresh));
        mockMvc.perform(auth(get("/api/auth/me"), newAccess)).andExpect(status().isOk());

        // 旧刷新令牌重放 → 判被盗，401 REFRESH_REPLAY，整段会话吊销
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_REPLAY"));

        // 会话已被重放吊销：新访问令牌随即失效，新刷新令牌也不能再换
        mockMvc.perform(auth(get("/api/auth/me"), newAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshOnlyOneSucceeds() throws Exception {
        JsonNode pair = loginAdmin("WEB");
        String sharedRefresh = refresh(pair);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"" + sharedRefresh + "\"}"))
                            .andReturn();
                    if (result.getResponse().getStatus() == 200) {
                        success.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, success.get(), "并发刷新必须恰好一方成功（CAS 一次性轮换）");
        assertEquals(threads - 1, rejected.get());
    }

    @Test
    void logoutInvalidatesAccessAndRefreshImmediately() throws Exception {
        JsonNode pair = loginAdmin("APP");
        String access = bearer(pair);
        String refreshToken = refresh(pair);

        // 登录态可用
        mockMvc.perform(auth(get("/api/auth/me"), access)).andExpect(status().isOk());

        // 登出
        mockMvc.perform(auth(post("/api/auth/logout"), access)).andExpect(status().isOk());

        // 旧访问令牌立即失效（不是等 30 分钟过期）
        mockMvc.perform(auth(get("/api/auth/me"), access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        // 刷新令牌同样失效，不能换新
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // 登出后令牌已无效：再次登出在认证过滤器层即被 401（业务语义仍为幂等——再登无害）
        mockMvc.perform(auth(post("/api/auth/logout"), access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeForcesAllDevicesOffline() throws Exception {
        // 用专用账号，避免改共享 admin 密码影响同类其他用例（同内存库、方法间不重置）
        String adminToken = bearer(loginAdmin("WEB"));
        createUser(adminToken, "pwuser", "pw@12345", "\"DISPATCHER\"");

        // 同一账号 WEB 与 APP 两端各自登录，会话独立并存
        JsonNode web = login("pwuser", "pw@12345", "WEB");
        JsonNode app = login("pwuser", "pw@12345", "APP");
        mockMvc.perform(auth(get("/api/auth/me"), bearer(web))).andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/auth/me"), bearer(app))).andExpect(status().isOk());

        // WEB 端改密
        mockMvc.perform(auth(post("/api/auth/password"), bearer(web))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"pw@12345\",\"newPassword\":\"pw@2026abc\"}"))
                .andExpect(status().isOk());

        // 两端访问令牌立即失效（tokenVersion 已变）
        mockMvc.perform(auth(get("/api/auth/me"), bearer(web)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_VERSION_CHANGED"));
        mockMvc.perform(auth(get("/api/auth/me"), bearer(app)))
                .andExpect(status().isUnauthorized());

        // 两端刷新令牌也都已吊销，无法换新
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh(web) + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh(app) + "\"}"))
                .andExpect(status().isUnauthorized());

        // 旧密码登录失败，新密码可重新登录
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pwuser\",\"password\":\"pw@12345\",\"clientType\":\"WEB\"}"))
                .andExpect(status().isUnauthorized());
        JsonNode relogin = login("pwuser", "pw@2026abc", "WEB");
        mockMvc.perform(auth(get("/api/auth/me"), bearer(relogin))).andExpect(status().isOk());

        // 原密码错误不能改密
        mockMvc.perform(auth(post("/api/auth/password"), bearer(relogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong-old\",\"newPassword\":\"another@123\"}"))
                .andExpect(status().isConflict());
    }
}
