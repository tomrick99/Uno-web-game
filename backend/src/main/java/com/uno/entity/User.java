package com.uno.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "uno_user")
@JsonIgnoreProperties({"password", "createdAt", "avatarData", "avatarContentType", "avatarUpdatedAt"})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;  // BCrypt 加密存储

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "avatar_data", columnDefinition = "LONGBLOB")
    private byte[] avatarData;

    @Column(name = "avatar_content_type", length = 32)
    private String avatarContentType;

    @Column(name = "avatar_updated_at")
    private LocalDateTime avatarUpdatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Constructors
    public User() {}

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public byte[] getAvatarData() { return avatarData; }
    public void setAvatarData(byte[] avatarData) { this.avatarData = avatarData; }

    public String getAvatarContentType() { return avatarContentType; }
    public void setAvatarContentType(String avatarContentType) { this.avatarContentType = avatarContentType; }

    public LocalDateTime getAvatarUpdatedAt() { return avatarUpdatedAt; }
    public void setAvatarUpdatedAt(LocalDateTime avatarUpdatedAt) { this.avatarUpdatedAt = avatarUpdatedAt; }

    @Transient
    public String getAvatarUrl() {
        if (id == null || avatarData == null || avatarData.length == 0 || avatarUpdatedAt == null) {
            return null;
        }
        long version = avatarUpdatedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return "/api/user/" + id + "/avatar?v=" + version;
    }
}
