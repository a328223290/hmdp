package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        // 查询redis数据库，看是否命中(以json形式存储)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 如果命中，则直接返回命中的结果
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        } else if ("".equals(shopJson)) {
            // 如果缓存中为空字符串，则说明数据库中不存在该数据
            return Result.fail("店铺不存在！");
        }
        // 如果未命中，则从数据库中查询
        Shop shop = getById(id);
        if (shop == null) {
            // 往缓存加入空字符串，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "");
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在！");
        }
        // 存入缓存，防止数据库压力过大
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回结果
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 采用Cache Aside Pattern: 在更新数据库的同时更新缓存，以保持缓存和数据库的一致性
        Long id = shop.getId();
        if (id == null) {
            Result.fail("商铺id为空。");
        }
        // 先更新数据库
        boolean isUpdate = updateById(shop);
        if (!isUpdate) {
            return Result.fail("更新商铺失败！");
        }
        // 删除缓存，而不是更新，防止写操作过多造成反复修改redis
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        // 返回结果
        return Result.ok();
    }
}
