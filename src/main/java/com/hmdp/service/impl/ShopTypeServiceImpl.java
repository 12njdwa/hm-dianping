package com.hmdp.service.impl;

import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Object querylist() {
        List<Object> values = stringRedisTemplate.opsForHash().values("cache:shoptype");
        if (values.size()!=0){
            ArrayList<ShopType> arrayList = new ArrayList();
            for (Object value : values) {
                ShopType shopType = JSON.parseObject(value.toString(), ShopType.class);
                arrayList.add(shopType);
            }
            return arrayList;
        }
        List<ShopType> list = list();
        if (list.size()==0||list==null){
            return Result.fail("没有查询出任何数据");
        }
        HashMap<String, String> objectObjectHashMap = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            String s = JSON.toJSONString(list.get(i));
            objectObjectHashMap.put(list.get(i).getId().toString(),s);
        }
        stringRedisTemplate.opsForHash().putAll("cache:shoptype",objectObjectHashMap);
        return list;
    }
}
