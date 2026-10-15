package com.picturesAI.generation_service.storage;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThumbnailGeneratorTest {

    private final ThumbnailGenerator generator = new ThumbnailGenerator();

    @Test
    void downscalesSquareImageToExactTargetSize() throws Exception {
        byte[] src = png(1024, 1024, Color.RED);

        byte[] result = generator.generate(src, 200);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(out.getHeight()).isEqualTo(200);
    }

    @Test
    void preservesAspectRatioForWideImage() throws Exception {
        byte[] src = png(1024, 512, Color.BLUE);

        byte[] result = generator.generate(src, 200);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(out.getHeight()).isEqualTo(100);
    }

    @Test
    void preservesAspectRatioForTallImage() throws Exception {
        byte[] src = png(512, 1024, Color.GREEN);

        byte[] result = generator.generate(src, 200);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(out.getWidth()).isEqualTo(100);
        assertThat(out.getHeight()).isEqualTo(200);
    }

    @Test
    void upscalesSmallSquareImageToTargetSize() throws Exception {
        byte[] src = png(50, 50, Color.ORANGE);

        byte[] result = generator.generate(src, 200);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(out.getHeight()).isEqualTo(200);
    }

    @Test
    void throwsOnBrokenInput() {
        byte[] broken = new byte[] { 1, 2, 3, 4, 5 };

        assertThatThrownBy(() -> generator.generate(broken, 200))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void throwsOnEmptyInput() {
        assertThatThrownBy(() -> generator.generate(new byte[0], 200))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void throwsOnNonPositiveMaxSize() throws IOException {
        byte[] src = png(100, 100, Color.PINK);

        assertThatThrownBy(() -> generator.generate(src, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] png(int w, int h, Color color) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}