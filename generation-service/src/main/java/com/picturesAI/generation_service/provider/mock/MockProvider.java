package com.picturesAI.generation_service.provider.mock;

import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ImageProvider;
import com.picturesAI.generation_service.provider.ProviderException;
import com.picturesAI.generation_service.provider.ProviderResponse;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Profile({"dev", "mock", "test", "e2e"})
@Order(1)
public class MockProvider implements ImageProvider {

    private static final String CONTENT_TYPE = "image/png";
    private static final int SIZE = 200;

    private static final Map<String, Color> COLOR_BY_SEED = Map.of(
            "cat", new Color(0xFF, 0xA5, 0x00),
            "dog", new Color(0x8B, 0x45, 0x13),
            "landscape", new Color(0x22, 0x8B, 0x22),
            "portrait", new Color(0xBC, 0x8F, 0x8F),
            "abstract", new Color(0x93, 0x70, 0xDB),
            "city", new Color(0x70, 0x80, 0x90));

    private static final List<String> SEED_ORDER =
            List.of("cat", "dog", "landscape", "portrait", "abstract", "city");

    private static final Map<String, String> KEYWORD_TO_SEED = buildKeywordMap();

    private final MockProviderProperties props;
    private final Random random;
    private final Map<String, byte[]> byKeyword;
    private final List<byte[]> allImages;

    @Autowired
    public MockProvider(MockProviderProperties props) {
        this(props, ThreadLocalRandom.current());
    }

    public MockProvider(MockProviderProperties props, Random random) {
        this.props = props;
        this.random = random;
        this.byKeyword = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : KEYWORD_TO_SEED.entrySet()) {
            byKeyword.put(entry.getKey(), imageForSeed(entry.getValue()));
        }
        this.allImages = SEED_ORDER.stream().map(MockProvider::imageForSeed).toList();
    }

    @Override
    public ProviderResponse generate(GenerationRequest request) {
        applyLatency();
        rollErrors();
        byte[] image = pickImage(request.prompt());
        return new ProviderResponse(image, CONTENT_TYPE, UUID.randomUUID().toString(), name());
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean supports(String model) {
        return true;
    }

    public static byte[] imageForSeed(String seedName) {
        Color color = COLOR_BY_SEED.get(seedName);
        if (color == null) {
            throw new IllegalArgumentException("Unknown seed: " + seedName);
        }
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, SIZE, SIZE);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void applyLatency() {
        long jitter = props.latencyJitterMs() > 0
                ? (long) (random.nextDouble() * props.latencyJitterMs())
                : 0L;
        long sleep = props.latencyMs() + jitter;
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException(500, "interrupted", e);
            }
        }
    }

    private void rollErrors() {
        double roll = random.nextDouble();
        if (roll < props.errorRate429()) {
            throw new ProviderException(429, "mock: rate limited");
        }
        if (roll < props.errorRate429() + props.errorRate5xx()) {
            throw new ProviderException(500, "mock: server error");
        }
    }

    private byte[] pickImage(String prompt) {
        if (prompt != null && !prompt.isBlank()) {
            String lower = prompt.toLowerCase();
            for (Map.Entry<String, byte[]> entry : byKeyword.entrySet()) {
                if (lower.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return allImages.get(random.nextInt(allImages.size()));
    }

    private static Map<String, String> buildKeywordMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cat", "cat");
        m.put("kitten", "cat");
        m.put("dog", "dog");
        m.put("puppy", "dog");
        m.put("landscape", "landscape");
        m.put("mountain", "landscape");
        m.put("portrait", "portrait");
        m.put("face", "portrait");
        m.put("abstract", "abstract");
        m.put("pattern", "abstract");
        m.put("city", "city");
        m.put("urban", "city");
        return Collections.unmodifiableMap(m);
    }
}