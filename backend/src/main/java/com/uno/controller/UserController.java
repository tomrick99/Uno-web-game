package com.uno.controller;

import com.uno.dto.request.LoginRequest;
import com.uno.dto.request.RegisterRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ApiResponse<User> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.register(request.getUsername(), request.getPassword());
            // 不返回密码
            user.setPassword(null);
            return ApiResponse.success("注册成功", user);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ApiResponse<User> login(@RequestBody LoginRequest request, HttpSession session) {
        return userService.login(request.getUsername(), request.getPassword())
                .map(user -> {
                    session.setAttribute("userId", user.getId());
                    session.setAttribute("username", user.getUsername());
                    user.setPassword(null);
                    return ApiResponse.success("登录成功", user);
                })
                .orElseGet(() -> ApiResponse.error(401, "用户名或密码错误"));
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpSession session) {
        session.invalidate();
        return ApiResponse.success("登出成功", null);
    }

    /**
     * 获取当前登录用户
     */
    @GetMapping("/me")
    public ApiResponse<User> getCurrentUser(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }
        return userService.findById(userId)
                .map(user -> {
                    user.setPassword(null);
                    return ApiResponse.success(user);
                })
                .orElseGet(() -> ApiResponse.error(404, "用户不存在"));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> uploadAvatar(@RequestParam("avatar") MultipartFile avatar,
                                                          HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }
        try {
            User user = userService.updateAvatar(userId, avatar);
            return ApiResponse.success("头像已保存", Map.of("avatarUrl", user.getAvatarUrl()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/{userId}/avatar")
    public ResponseEntity<byte[]> getAvatar(@PathVariable Long userId) {
        return userService.findById(userId)
                .filter(user -> user.getAvatarData() != null && user.getAvatarData().length > 0)
                .map(user -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(user.getAvatarContentType()))
                        .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                        .body(user.getAvatarData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
