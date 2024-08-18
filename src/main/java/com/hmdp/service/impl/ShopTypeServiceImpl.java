package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY_TTL;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopTypeService typeService;

    @Override
    public Result queryTypeList() {
        // 查询缓存
        String typeJsonList = stringRedisTemplate.opsForValue().get(SHOP_TYPE_KEY);
        if (StrUtil.isNotBlank(typeJsonList)) {
            List<ShopType> shopTypes = JSONUtil.toList(typeJsonList, ShopType.class);
            return Result.ok(shopTypes);
        }
        // 缓存不命中，查询数据库
        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        if (typeList == null || typeList.size() == 0) {
            return Result.fail("商铺类型不存在！");
        }
        // 存入缓存
        stringRedisTemplate.opsForValue().set(SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));
        stringRedisTemplate.expire(SHOP_TYPE_KEY, SHOP_TYPE_KEY_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
