package com.erlan.testtask.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.erlan.testtask.config.CacheConfig;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.enums.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.datasource.url=jdbc:h2:mem:redis-startup-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class RedisStartupCacheIntegrationTest {

    private static final String UNRELATED_KEY = "testtask:unrelated:startup-check";

    @Autowired private CacheManager cacheManager;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SeedStaleCacheRunner seedRunner;

    @Test
    void startupClearsOnlyTaskCache() {
        assertThat(seedRunner.seeded).isTrue();
        assertThat(cacheManager.getCache(CacheConfig.TASKS_CACHE).get(SimpleKey.EMPTY)).isNull();
        assertThat(redis.opsForValue().get(UNRELATED_KEY)).isEqualTo("keep");
        redis.delete(UNRELATED_KEY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StartupSeedConfig {

        @Bean
        SeedStaleCacheRunner seedStaleCacheRunner(CacheManager cacheManager, StringRedisTemplate redis) {
            return new SeedStaleCacheRunner(cacheManager, redis);
        }
    }

    static class SeedStaleCacheRunner implements ApplicationRunner, Ordered {

        private final CacheManager cacheManager;
        private final StringRedisTemplate redis;
        private boolean seeded;

        SeedStaleCacheRunner(CacheManager cacheManager, StringRedisTemplate redis) {
            this.cacheManager = cacheManager;
            this.redis = redis;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void run(ApplicationArguments args) {
            TaskResponse stale = new TaskResponse(99L, "Stale", null, TaskStatus.TODO,
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
            cacheManager.getCache(CacheConfig.TASKS_CACHE).put(SimpleKey.EMPTY, List.of(stale));
            redis.opsForValue().set(UNRELATED_KEY, "keep");
            seeded = true;
        }
    }
}
