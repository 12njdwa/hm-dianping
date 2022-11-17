package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Test
    void a(){
        stringRedisTemplate.opsForHash().put("cache:shoptype","1","hhhhhhhh");
        List<Object> values = stringRedisTemplate.opsForHash().values("cache:shoptype");
        for (Object value : values) {
            System.out.println(value);

        }

    }


}
