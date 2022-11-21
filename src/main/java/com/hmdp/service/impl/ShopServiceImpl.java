package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.sun.istack.internal.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Object queryById(Long id) throws InterruptedException {
//        return queryWithPenetration(id);
//        return queryWithLogicalExpire(id);
//        return queryWithMutex(id);
        return cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,id1->getById(id1),Shop.class,2L,TimeUnit.MINUTES);
    }

    //存空值 解决缓存穿透
    public Object queryWithPenetration(Long id) {
        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        Shop shop;
        if (StrUtil.isNotBlank(s)) {
            shop = JSON.parseObject(s, Shop.class);
            return shop;
        }
        if (s != null) {
            return Result.fail("店铺不存在");
        }
        shop = getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(idstring, "", 1L, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        stringRedisTemplate.opsForValue().set(idstring, JSON.toJSONString(shop), 30L, TimeUnit.MINUTES);
        return shop;
    }

    //互斥锁 解决缓存击穿
    public Object queryWithMutex(Long id) {
        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        Shop shop = null;
        String lockname = null;
        boolean islock = false;
        try {
            if (StrUtil.isNotBlank(s)) {
                shop = JSON.parseObject(s, Shop.class);
                return shop;
            }
            if (shop != null) {
                return null;
            }
            lockname = "lock:shop:" + id.toString();
            islock = getlock(lockname);
            if (!islock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //存空值解决缓存穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(idstring, "", 2L, TimeUnit.MINUTES);
                return null;
            }
            shop = getById(id);
            //模拟一下延时
            Thread.sleep(200);
            stringRedisTemplate.opsForValue().set(idstring, JSON.toJSONString(shop), 30L, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (islock) {
                unlock(lockname);
            }
        }
        return shop;
    }


    //逻辑过期 解决缓存击穿
    public Object queryWithLogicalExpire(Long id) throws InterruptedException {
        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        RedisData redisData = null;
        String lockkey = null;
        //第一次，缓存中没有数据，从数据库中查出并存入redis
        if (StrUtil.isBlank(s)) {
            return saveShop2Redis(id, 120L);
        }
        redisData = JSONUtil.toBean(s, RedisData.class);
        //因为Data是Object对象，所以需要再次转换一下。 否则直接强转的话会报错
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        lockkey = "lock:shop" + id.toString();
        //逻辑过期后，获取互斥锁
        boolean getlock = getlock(lockkey);
        //判断是否获取到了锁，得到锁后开启新线程，实现缓存重建
        if (getlock) {
            //创建新线程去做缓存的重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    saveShop2Redis(id, 120L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock("lock:shop" + id);
                }
            });
        }
        return shop;
    }


    @Override
    @Transactional
    public Result updateshop(Shop shop) {
        //更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        System.out.println(shop.toString());
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    public Shop saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSON.toJSONString(redisData));
        return shop;
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
