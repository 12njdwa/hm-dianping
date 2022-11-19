package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.sun.istack.internal.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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

    @Override
    public Object queryById(Long id) {
//        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
//        String s = stringRedisTemplate.opsForValue().get(idstring);
//        Shop shop;
//        if (StrUtil.isNotBlank(s)){
//            shop=JSON.parseObject(s,Shop.class);
//            return Result.ok(shop);
//        }
//        shop= getById(id);
//        if (shop==null){
//            return Result.fail("店铺不存在");
//        }
//        stringRedisTemplate.opsForValue().set(idstring,JSON.toJSONString(shop));
//        stringRedisTemplate.expire(idstring,30, TimeUnit.MINUTES);
//        return Result.ok(shop);
        return queryWithMutex(id);
    }

    //互斥锁 解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        Shop shop = null;
        String lockname = null;
        try {
            if (StrUtil.isNotBlank(s)) {
                shop = JSON.parseObject(s, Shop.class);
                return shop;
            }
            if (shop != null) {
                return null;
            }
            lockname = "lock:shop:" + id.toString();
            boolean islock = getlock(lockname);
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
            if (StrUtil.isNotBlank(lockname)) {
                unlock(lockname);
            }
        }
        return shop;
    }


    //逻辑过期 解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        RedisData redisData = null;
        String lockname = null;
        if (StrUtil.isBlank(s)) {
            return null;
        }
        redisData = JSON.parseObject(s, RedisData.class);
        Shop data = (Shop)redisData.getData();


        if (shop != null) {
            return null;
        }
        lockname = "lock:shop:" + id.toString();
        boolean islock = getlock(lockname);
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

        return shop;
    }


    public void saveShop2Redis(Long id, Long expireSeconds) {
        RedisData redisData = new RedisData();
        redisData.setData(getById(id));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
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
