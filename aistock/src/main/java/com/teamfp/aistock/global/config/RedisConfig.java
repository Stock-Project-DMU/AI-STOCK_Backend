package com.teamfp.aistock.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 서버와 연결하는 설정.
 */
@Configuration
public class RedisConfig {

    // RedisConnectionFactory는 커스텀 빈으로 정의하지 않는다.
    // application.yml의 spring.data.redis.host / port / password / lettuce.pool.*
    // 값을 Spring Boot Data Redis 오토설정이 자동으로 읽어 구성한다.
    // (2-argument 생성자로 직접 만들면 password, lettuce.pool.* 설정이 반영되지 않아
    //  AWS ElastiCache AUTH 환경에서 연결이 실패할 수 있다.)

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
