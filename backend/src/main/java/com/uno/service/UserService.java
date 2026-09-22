package com.uno.service;

import com.uno.entity.User;
import com.uno.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class UserService {

    static final long MAX_AVATAR_BYTES = 400 * 1024;
    static final int AVATAR_SIZE = 256;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 用户注册
     */
    public User register(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        String encodedPassword = passwordEncoder.encode(password);
        User user = new User(username, encodedPassword);
        return userRepository.save(user);
    }

    /**
     * 用户登录验证
     */
    public Optional<User> login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (passwordEncoder.matches(password, user.getPassword())) {
                return Optional.of(user);
            }
        }
        return Optional.empty();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public User updateAvatar(Long userId, MultipartFile avatar) {
        if (avatar == null || avatar.isEmpty()) {
            throw new IllegalArgumentException("请选择头像图片");
        }
        if (avatar.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("头像文件过大");
        }

        String contentType = avatar.getContentType();
        if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType)) {
            throw new IllegalArgumentException("仅支持 JPEG 或 PNG 头像");
        }

        byte[] bytes;
        BufferedImage decoded;
        try {
            bytes = avatar.getBytes();
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取头像图片");
        }
        if (decoded == null) {
            throw new IllegalArgumentException("头像内容不是有效图片");
        }
        if (decoded.getWidth() != AVATAR_SIZE || decoded.getHeight() != AVATAR_SIZE) {
            throw new IllegalArgumentException("头像必须为 256×256 像素");
        }
        if (!matchesSignature(bytes, contentType)) {
            throw new IllegalArgumentException("头像格式与文件内容不匹配");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setAvatarData(bytes);
        user.setAvatarContentType(contentType);
        user.setAvatarUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private boolean matchesSignature(byte[] bytes, String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xFF
                    && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF;
        }
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;
    }
}
