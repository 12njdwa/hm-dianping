package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("该博客不存在");
        }
        User user = userService.getById(blog.getUserId());
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        blog.setIsLike(isBlogLiked(blog));
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            blog.setIsLike(isBlogLiked(blog));
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "cache:blog:islike:" + id;
//        前五的点赞用户
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (range == null || range.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
//        解析出其中的用户id
        List<Long> collect = range.stream().map(Long::valueOf).collect(Collectors.toList());
//        根据用户id查询用户
        List<UserDTO> userDTO = userService.queryByIdList(collect).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTO);
    }

    @Override
    public Result likeBlog(Long id) {
        String currentUserId = UserHolder.getUser().getId().toString();
        String key = "cache:blog:islike:" + id;
        if (stringRedisTemplate.opsForZSet().score(key, currentUserId) != null) {
            if (update().setSql("liked = liked - 1").eq("id", id).update()) {
                stringRedisTemplate.opsForZSet().remove(key, currentUserId);
            }
        } else {
            if (update().setSql("liked = liked + 1").eq("id", id).update()) {
                stringRedisTemplate.opsForZSet().add(key, currentUserId, System.currentTimeMillis());
            }
        }
        return Result.ok();
    }


    //判断是否被当前用户点赞过了
    private boolean isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user==null){
            return false;
        }
        String currentUserId = user.getId().toString();
        String key = "cache:blog:islike:" + blog.getId();
        return BooleanUtil.isTrue(stringRedisTemplate.opsForZSet().score(key, currentUserId) != null);
    }
}
