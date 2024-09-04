package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
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
//        Shop shop = queryWithPassThrough(id);
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("Shop不存在。");
        }
        return Result.ok(shop);
    }

    // 不解决缓存击穿问题的版本
    public Shop queryWithPassThrough(Long id) {
        // 查询redis数据库，看是否命中(以json形式存储)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 如果命中，则直接返回命中的结果
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        } else if ("".equals(shopJson)) {
            // 如果缓存中为空字符串，则说明数据库中不存在该数据
            return null;
        }
        // 如果未命中，则从数据库中查询
        Shop shop = getById(id);
        if (shop == null) {
            // 往缓存加入空字符串，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "");
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存入缓存，防止数据库压力过大
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回结果
        return shop;
    }

    // 通过互斥锁解决缓存穿透问题的版本
    public Shop queryWithMutex(Long id) {
        // 查询redis数据库，看是否命中(以json形式存储)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 如果命中，则直接返回命中的结果
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        } else if ("".equals(shopJson)) {
            // 如果缓存中为空字符串，则说明数据库中不存在该数据
            return null;
        }
        // 为解决缓存穿透，防止热点数据过期或者构建缓存较为复杂的数据，引入互斥锁。
        try {
            while (true) {
                boolean flag = tryLock(LOCK_SHOP_KEY + id);
                // 退出前先检查缓存是否已经替换了，若替换了则直接返回缓存数据即可
                String curShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                if (StrUtil.isNotBlank(curShopJson)) {
                    return JSONUtil.toBean(curShopJson, Shop.class);
                } else if ("".equals(curShopJson)) {
                    return null;
                }
                if (flag) break;
                Thread.sleep(50);
            }
            // 如果未命中，则从数据库中查询
            Shop shop = getById(id);
            // todo: 模拟重建的延时，非必要可以注释掉
            Thread.sleep(200);
            if (shop == null) {
                // 往缓存加入空字符串，解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "");
                stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存入缓存，防止数据库压力过大
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
            stringRedisTemplate.expire(CACHE_SHOP_KEY + id, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            // 返回结果前需要释放互斥锁
            unlock(LOCK_SHOP_KEY + id);
            // 返回结果
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
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
