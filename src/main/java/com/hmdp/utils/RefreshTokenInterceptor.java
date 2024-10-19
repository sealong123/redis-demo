package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1. 获取session。
//        HttpSession session = request.getSession();
//        //2. 获取session中的用户。
//        Object user = session.getAttribute("user");
/*
        //3. 判断用户是否存在。
        if(user == null){
            //4. 不存在，拦截,返回401状态码。
            response.setStatus(401);
            return false;
        }
        //5. 存在，保存用户信息到 ThreadLocal。
        UserHolder.saveUser((UserDTO) user);
        //6. 放行。*/

        //1. 获取请求头中的token。
//        System.out.println("6666");
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){
//            response.setStatus(401);
            return true;
        }

        //2. 基于token获取redis中的用户。
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //3. 判断用户是否存在。
        if(userMap.isEmpty()){
//            //4. 不存在，拦截。
//            response.setStatus(401);
//            return false;
            return true;
        }

        //5. 将查询到的hash数据转为UserDTO对象。
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //6. 保存用户信息到 ThreadLocal。
        UserHolder.saveUser(userDTO);

        //7. 刷新token有效期。
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8. 放行。
        return true;
//        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        System.out.println("999");222222
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
