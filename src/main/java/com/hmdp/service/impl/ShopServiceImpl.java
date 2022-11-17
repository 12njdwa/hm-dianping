package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
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
        String idstring = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(idstring);
        Shop shop;
        if (StrUtil.isNotBlank(s)){
            shop=JSON.parseObject(s,Shop.class);
            return Result.ok(shop);
        }
        shop= getById(id);
        if (shop==null){
            return Result.fail("店铺不存在");
        }
        stringRedisTemplate.opsForValue().set(idstring,JSON.toJSONString(shop));
        stringRedisTemplate.expire(idstring,30, TimeUnit.MINUTES);
        return Result.ok(shop);
    }
}
