package com.hmdp.service.impl;

import cn.hutool.captcha.generator.RandomGenerator;
import com.alibaba.fastjson.JSON;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 鄢
 * @since 2022-11-3
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //基于session存储验证码
////          校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            //          如果不符合，返回错误信息
//            return Result.fail("手机格式错误");
//        }
////        如果符合，生成验证码        随机生成6位数的验证码
//        //273681    纯数字
//        String code = RandomUtil.randomNumbers(6);
//        //gq4ryp    字母和数字组合
//        RandomGenerator randomGenerator = new RandomGenerator(6);
//        String generate = randomGenerator.generate();
////        保存验证码到session
//        session.setAttribute("code", generate);
//        session.setAttribute("phone", phone);
////        发送验证码
//        log.debug("发送短信验证码成功" + session.getAttribute("code"));
//        return Result.ok();


        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误");
        }
        RandomGenerator randomGenerator = new RandomGenerator(6);
        String code = randomGenerator.generate();

        stringRedisTemplate.opsForValue().set("login:code:"+phone,code,2, TimeUnit.MINUTES);

//        发送验证码
        log.debug("发送短信验证码成功" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //session方式
//        String phone = loginForm.getPhone();
//        String code = loginForm.getCode();
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            return Result.fail("手机号格式错误");
//        }
//        Object sessionphone = session.getAttribute("phone");
//        Object sessioncode = session.getAttribute("code");
//
//        if (sessioncode == null || !phone.equals(sessionphone.toString()) || !code.equals(sessioncode.toString())) {
//            return Result.fail("验证码错误或手机号与验证码不对应");
//        }
//
//        User user = query().eq("phone", phone).one();
//
////        判断该手机号是否存在，如果不存在，则注册,否则直接登录成功
//        if (user == null) {
//            user=createUserWithPhone(phone);
//            save(user);
//        }
//        UserDTO userDTO = new UserDTO();
//        //将user对象转换成userDTO再给session，这样服务器存储压力会小一点
//        BeanUtils.copyProperties(user,userDTO);
//        session.setAttribute("user", userDTO);
//
//        return Result.ok();

        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String sessioncode =stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);

        if (sessioncode == null ||!code.equals(sessioncode)) {
            return Result.fail("验证码错误或手机号与验证码不对应");
        }

        User user = query().eq("phone", phone).one();

//        判断该手机号是否存在，如果不存在，则注册,否则直接登录成功
        if (user == null) {
            user=createUserWithPhone(phone);
            save(user);
        }
        UserDTO userDTO = new UserDTO();
        //随机生成token，作为登录令牌，就相当于sessionid
        String token = UUID.randomUUID().toString(true);

        BeanUtils.copyProperties(user,userDTO);
        //UserDTO转Map,这是alibaba的FASTJSON中的方法，（百度的）
        Map<String, String> userMap =JSON.parseObject(JSON.toJSONString(userDTO), new TypeReference<Map<String, String>>(){});

        String s = RedisConstants.LOGIN_USER_KEY+ token;
        stringRedisTemplate.opsForHash().putAll(s,userMap);
        stringRedisTemplate.expire(s,30,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        LocalDateTime now=LocalDateTime.now();
        User user = new User();
        user.setPhone(phone);
        user.setPassword("123456");
        user.setCreateTime(now);
        user.setUpdateTime(now);
        user.setNickName("user_"+RandomUtil.randomString(10));
        return user;
    }
}
