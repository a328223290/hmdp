package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;


    @Override
    public Result seckillVoucher(Long id) {
        // 1. 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(id);
        if (seckillVoucher == null) {
            return Result.fail("该ID无效。");
        }
        // 2. 判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始。");
        }
        // 3. 判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束。");
        }
        // 4. 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足。");
        }
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        // ！！！为了保证一人一单且不对所有用户加锁，这里用userId对应的字符串（且必须采用intern方法指向字符串池中的保证是同一个字符串）作为对象锁。
        synchronized (userId.toString().intern()) {
            // TODO: 这里还不是特别了解，大概的原理是spring框架调用带有@Transactional注解的方法时，事务想要生效，还得利用代理来生效。
            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderService.createVoucherOrder(id);
        }
        // 这样一来就可以保证spring框架先提交事务，再释放锁。防止先释放锁，再提交事务带来的并发问题。（比如事务尚未提交，又有新的线程）
    }

    @Transactional
    public Result createVoucherOrder(Long id) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", id).count();
        if (count > 0) {
            return Result.fail("一个人只能购买一次该优惠券。");
        }
        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", id)
                // 乐观锁实现思路1 - 版本号 - 这种情况会导致不能全部卖完
//                .eq("stock", seckillVoucher.getStock())
                // 直接把条件改成stock > 0，依赖mysql的行锁去解决，但这个很难说是一个乐观锁吧。。
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足。");
        }
        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(id);
        voucherOrderService.save(voucherOrder);
        // 7. 返回订单id
        return Result.ok(id);
    }
}
