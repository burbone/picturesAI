package com.picturesAI.generation_service.storage;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class ThumbnailGenerator {

    public byte[] generate(byte[] original, int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        BufferedImage src = read(original);
        BufferedImage dst = scale(src, maxSize);
        return writePng(dst);
    }

    private BufferedImage read(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                throw new UnsupportedImageFormatException(
                        "ImageIO could not decode image (unknown format)");
            }
            return img;
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to read image", e);
        }
    }

    private BufferedImage scale(BufferedImage src, int maxSize) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            throw new UnsupportedImageFormatException("Image has zero dimensions");
        }
        int targetW;
        int targetH;
        if (srcW >= srcH) {
            targetW = maxSize;
            targetH = Math.max(1, (int) Math.round((double) srcH * maxSize / srcW));
        } else {
            targetH = maxSize;
            targetW = Math.max(1, (int) Math.round((double) srcW * maxSize / srcH));
        }

        int type = src.getType() == BufferedImage.TYPE_CUSTOM
                ? BufferedImage.TYPE_INT_RGB
                : src.getType();
        BufferedImage dst = new BufferedImage(targetW, targetH, type);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return dst;
    }

    private byte[] writePng(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            boolean written = ImageIO.write(img, "png", out);
            if (!written) {
                throw new UnsupportedImageFormatException(
                        "ImageIO has no PNG writer available");
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to encode PNG", e);
        }
        return out.toByteArray();
    }
}