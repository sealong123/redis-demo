package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long>   SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //1. 获取订单中的信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + UserId, stringRedisTemplate);
        Long UserId = voucherOrder.getId();
        RLock lock = redissonClient.getLock("lock:order:" + UserId);
        // 获取锁
//        boolean isLock = lock.tryLock(1200);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            // 获取锁失败
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);          //防止事务失效
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long UserId = UserHolder.getUser().getId();
        //1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserId.toString()
        );
        //2. 判断结果是否为 0
        if(result != 0){
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        //为0，有购买资格，将下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserId);
        voucherOrder.setVoucherId(voucherId);
            //放入阻塞队列
        orderTasks.add(voucherOrder);
            // 获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2. 判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        //3. 判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //4. 判断库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        Long UserId = UserHolder.getUser().getId();
//
////        synchronized (UserId.toString().intern()) {            //无法解决集群的并发安全问题
////            // 获取代理对象（事务）
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);          //防止事务失效
////        }
//
//        // 创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + UserId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + UserId);
//        // 获取锁
////        boolean isLock = lock.tryLock(1200);
//        boolean isLock = lock.tryLock();
//
//        if(!isLock){
//            // 获取锁失败
//            return Result.fail("一个人只允许下一单");
//        }
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);          //防止事务失效
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }

//    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5. 一人一单
        Long UserId = voucherOrder.getUserId();

        //5.1 查询订单
        Integer count = query().eq("user_id", UserId).eq("voucher_id", voucherOrder).count();
        //5.2 判断是否存在
        if (count > 0) {
            log.error("不能重复购买");
        }
        //6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder).gt("stock", 0)        //乐观锁，cas法。
                .update();
        if (!success) {
            log.error("库存不足");
        }
        //7. 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserId);
//        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
//        //8. 返回订单id
//        return Result.ok(orderId);
    }
}
