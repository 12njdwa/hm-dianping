package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleReidsLock implements ILock {
    //锁名称
    private String lockName;
    private static final String KEY_PREFIX = "lock:";
    private String uuid = UUID.randomUUID(true).toString();

    private static final DefaultRedisScript UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private StringRedisTemplate stringRedisTemplate;

    public SimpleReidsLock(String lockName, StringRedisTemplate stringRedisTemplate) {
        this.lockName = lockName;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public SimpleReidsLock() {
    }

    @Override
    public boolean getlock(long timeoutSec) {
//        String id = String.valueOf(Thread.currentThread().getId());
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + lockName, uuid, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    //这样无法保证整个解锁的原子性，也就可能导致安全问题，但发生的概率很小。
    //    @Override
//    public void unlock() {
////        String s = stringRedisTemplate.opsForValue().get(KEY_PREFIX + lockName);
////        //解决 当执行业务的时间超过redis自动过期锁的时候，释放其他线程锁的问题
////        if (s.equals(uuid)){
////            stringRedisTemplate.delete(KEY_PREFIX+lockName);
////        }
//    }

    //利用lua脚本保证了操作的原子性
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + lockName), uuid);
    }
}
