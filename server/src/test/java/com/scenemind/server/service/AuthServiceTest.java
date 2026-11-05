package com.scenemind.server.service;

import com.scenemind.server.dto.AuthRequest;
import com.scenemind.server.dto.AuthResponse;
import com.scenemind.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公网部署的安全边界：用户名维度防撞库、IP 维度防轮换爆破、注册开关。
 */
class AuthServiceTest {

    @SuppressWarnings("unchecked")
    private static AuthService service(StringRedisTemplate redis, boolean registrationEnabled) {
        return new AuthService(redis, mock(UserMapper.class), registrationEnabled);
    }

    @Test
    void blocksIpAfterTooManyFailuresEvenWhenUsernameIsFresh() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:login-failures:alice")).thenReturn(null);
        when(values.get("auth:login-ip-failures:1.2.3.4")).thenReturn("30");

        AuthService auth = service(redis, true);

        assertFalse(auth.loginAttemptAllowed("alice", "1.2.3.4"));
        assertTrue(auth.loginAttemptAllowed("alice", "5.6.7.8"),
                "同一用户名换一个来源 IP 不应被其他 IP 的失败计数影响");
    }

    @Test
    void blocksUsernameAfterEightFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:login-failures:alice")).thenReturn("8");

        AuthService auth = service(redis, true);

        assertFalse(auth.loginAttemptAllowed("alice", "1.2.3.4"));
    }

    @Test
    void registrationCanBeDisabledForPublicDeployment() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        AuthService auth = service(redis, false);

        AuthResponse response = auth.register(new AuthRequest("alice", "password123", "Alice"));

        assertEquals(403, response.code());
        assertTrue(response.msg().contains("公开注册已关闭"));
    }
}
