package com.uno.controller;

import com.uno.auth.ActiveLoginService;
import com.uno.dto.request.LoginRequest;
import com.uno.dto.request.RegisterRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final ActiveLoginService activeLoginService;

    public UserController(UserService userService, ActiveLoginService activeLoginService) {
        this.userService = userService;
        this.activeLoginService = activeLoginService;
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
                    activeLoginService.activate(user, session);
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
        activeLoginService.deactivate(session);
        session.invalidate();
        return ApiResponse.success("登出成功", null);
    }

    /**
     * 获取当前登录用户
     */
    @GetMapping("/me")
    public ApiResponse<User> getCurrentUser(HttpSession session) {
        if (activeLoginService.isReplaced(session)) {
            return ApiResponse.error(401, ActiveLoginService.SESSION_REPLACED_MESSAGE);
        }
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
}
