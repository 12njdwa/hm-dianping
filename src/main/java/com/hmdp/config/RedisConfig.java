package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
     public RedissonClient redissonClient(){
        Config config=new Config();
        config.useSingleServer().setAddress("redis://0.0.0.0:55000").setPassword("redispw");
        return Redisson.create(config);
    }
}
