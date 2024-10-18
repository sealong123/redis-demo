package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
    public Result queryById(Long id) throws InterruptedException {
//        //缓存穿透。
//        Shop shop = queryWithPassThrough(id);
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在。");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = null;
        Shop shop = null;
        try {
            //1. 从redis查询商品缓存。
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //2. 判断是否存在。
            if(StrUtil.isNotBlank(shopJson)){
                //3. 存在，返回。
                shop = JSONUtil.toBean(shopJson,Shop.class);
                return shop;
            }
            //判断命中的是否是空字符串。
            if(shopJson != null){
                return null;
            }
            //4. 实现缓存重建。
            //4.1 获取互斥锁。
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean flag = tryLock(lockKey);
            //4.2 判断是否获取成功。
            if(flag == false){
                //4.3 失败，休眠并重试。
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功，根据id查数据库。
            shop = getById(id);
            if(shop == null){
                //将空值写入redis，解决缓存穿透。
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息。
                return null;
            }

            //5. 写入redis。
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //6. 释放互斥锁。
            unlock(lockKey);
        }

        //8. 返回。
        return shop;
    }

    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询商品缓存。
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在。
        if(StrUtil.isNotBlank(shopJson)){
            //3. 存在，返回。
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中的是否是空字符串。
        if(shopJson != null){
            return null;
        }
        //4. 不存在，根据id查数据库。
        Shop shop = getById(id);
        if(shop == null){
            //将空值写入redis，解决缓存穿透。
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息。
            return null;
        }
        //5. 写入redis。
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空。");
        }
        String key = CACHE_SHOP_KEY + id;
        //1. 更新数据库。
        updateById(shop);
        //2. 删除缓存。
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
