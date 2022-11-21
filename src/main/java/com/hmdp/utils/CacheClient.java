package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.sun.istack.internal.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value),time,unit);
    }

    //    存空值 解决缓存穿透
    public <R,ID> R queryWithPenetration(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> fallback, Long time, TimeUnit unit){
        String idstring =keyPrefix+ id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        if (StrUtil.isNotBlank(s)) {
            return JSON.parseObject(s, type);
        }
        if (s != null) {
            return null;
        }
        R r= fallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(idstring, "", 1L, TimeUnit.MINUTES);
            return null;
        }
        this.set(idstring,r,time,unit);
        return r;
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Function<ID,R> fallback,Class<R> type,Long time,TimeUnit unit) throws InterruptedException {
        String idstring = keyPrefix + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        RedisData redisData = null;
        String lockkey = null;
        //第一次，缓存中没有数据，从数据库中查出并存入redis
        if (StrUtil.isBlank(s)) {
            R r = fallback.apply(id);
            return saveShop2Redis(idstring,r,time,unit);
        }
        redisData = JSONUtil.toBean(s, RedisData.class);
        //因为Data是Object对象，所以需要再次转换一下。 否则直接强转的话会报错
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        lockkey = "lock:shop" + id.toString();
        //逻辑过期后，获取互斥锁
        boolean getlock = getlock(lockkey);
        //判断是否获取到了锁，得到锁后开启新线程，实现缓存重建
        if (getlock) {
            //创建新线程去做缓存的重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(idstring,r,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock("lock:shop" + id);
                }
            });
        }
        return r;
    }

//缓存重建
    public <R,ID>  R saveShop2Redis(String key,R r, Long expireSeconds,TimeUnit unit) {
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(r);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireSeconds)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(redisData));
        return r;
    }
    private boolean getlock(String key) {
        @Nullable
        Boolean exist = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return exist;
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
