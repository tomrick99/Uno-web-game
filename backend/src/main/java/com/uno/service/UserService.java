package com.uno.service;

import com.uno.entity.User;
import com.uno.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Base64;

@Service
@Transactional
public class UserService {

    private static final int MAX_AVATAR_BYTES = 256 * 1024;

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

    public User updateAvatar(Long userId, String avatarDataUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setAvatarDataUrl(validateAvatarDataUrl(avatarDataUrl));
        return userRepository.save(user);
    }

    private String validateAvatarDataUrl(String avatarDataUrl) {
        if (avatarDataUrl == null || avatarDataUrl.isBlank()) {
            return null;
        }

        int commaIndex = avatarDataUrl.indexOf(',');
        if (commaIndex <= 0) {
            throw new IllegalArgumentException("头像图片格式无效");
        }
        String prefix = avatarDataUrl.substring(0, commaIndex).toLowerCase();
        if (!prefix.equals("data:image/jpeg;base64")
                && !prefix.equals("data:image/png;base64")
                && !prefix.equals("data:image/webp;base64")) {
            throw new IllegalArgumentException("头像只支持 JPEG、PNG 或 WebP 图片");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(avatarDataUrl.substring(commaIndex + 1));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("头像图片格式无效");
        }
        if (decoded.length == 0 || decoded.length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("头像图片过大，请裁剪后重试");
        }
        if (!matchesImageSignature(prefix, decoded)) {
            throw new IllegalArgumentException("头像图片内容无效");
        }
        return avatarDataUrl;
    }

    private boolean matchesImageSignature(String prefix, byte[] bytes) {
        if (prefix.contains("jpeg")) {
            return bytes.length >= 3
                    && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8
                    && (bytes[2] & 0xff) == 0xff;
        }
        if (prefix.contains("png")) {
            return bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4e
                    && bytes[3] == 0x47;
        }
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }
}
