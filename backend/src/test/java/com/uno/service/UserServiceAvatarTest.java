package com.uno.service;

import com.uno.entity.User;
import com.uno.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceAvatarTest {

    @Test
    void savesValidatedCroppedAvatarAndBuildsVersionedUrl() throws Exception {
        UserRepository repository = mock(UserRepository.class);
        User user = new User("alice", "pw");
        user.setId(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(user));
        when(repository.save(same(user))).thenReturn(user);
        UserService service = new UserService(repository);

        byte[] jpeg = imageBytes(256, 256, "jpg");
        MockMultipartFile upload = new MockMultipartFile("avatar", "avatar.jpg", "image/jpeg", jpeg);

        User saved = service.updateAvatar(7L, upload);

        assertEquals("image/jpeg", saved.getAvatarContentType());
        assertEquals(jpeg.length, saved.getAvatarData().length);
        assertNotNull(saved.getAvatarUpdatedAt());
        assertTrue(saved.getAvatarUrl().startsWith("/api/user/7/avatar?v="));
        verify(repository).save(same(user));
    }

    @Test
    void rejectsImageThatWasNotCroppedToRequiredSquareSize() throws Exception {
        UserService service = new UserService(mock(UserRepository.class));
        byte[] jpeg = imageBytes(300, 256, "jpg");
        MockMultipartFile upload = new MockMultipartFile("avatar", "wide.jpg", "image/jpeg", jpeg);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateAvatar(7L, upload));

        assertTrue(error.getMessage().contains("256×256"));
    }

    @Test
    void rejectsSpoofedImageContent() {
        UserService service = new UserService(mock(UserRepository.class));
        MockMultipartFile upload = new MockMultipartFile(
                "avatar", "avatar.jpg", "image/jpeg", "not-an-image".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.updateAvatar(7L, upload));
    }

    private byte[] imageBytes(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
