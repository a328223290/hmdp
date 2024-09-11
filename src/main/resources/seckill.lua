---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by kai.
--- DateTime: 10/09/24 上午1:04
---
-- 1. 参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2. 数据key
-- 2.1 库存key
local stockKey = 'seckill:stock' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order' .. voucherId

-- 3. 脚本业务
-- 3.1 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end

-- 3.2 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 如果是重复下单，返回2
    return 2
end

-- 3.3 扣库存
redis.call('incrby', stockKey, -1)
-- 3.4 将userId存入当前优惠券的set集合
redis.call('sadd', orderKey, userId)
-- 3.5 返回0
return 0




