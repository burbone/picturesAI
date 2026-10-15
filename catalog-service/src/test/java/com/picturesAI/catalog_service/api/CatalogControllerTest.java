package com.picturesAI.catalog_service.api;

import com.picturesAI.catalog_service.api.dto.AuthorResponse;
import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.api.dto.TagCountResponse;
import com.picturesAI.catalog_service.service.CatalogQueryService;
import com.picturesAI.catalog_service.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CatalogController.class)
class CatalogControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CatalogQueryService query;

    @MockitoBean
    private RecommendationService recommendations;

    private ImageResponse sampleImage(UUID id) {
        return new ImageResponse(
                id,
                UUID.randomUUID(),
                "a cat",
                "https://minio/orig",
                "https://minio/thumb",
                "pollinations",
                Instant.parse("2025-01-01T00:00:00Z"),
                List.of("cat", "cute"),
                new AuthorResponse(UUID.randomUUID(), "alice", "Alice", null, false));
    }

    @Test
    void feedReturnsCursorPage() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.getFeed(isNull(), eq(20)))
                .thenReturn(new CursorPage<>(List.of(sampleImage(id)), "next", true));

        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].prompt").value("a cat"))
                .andExpect(jsonPath("$.items[0].tags[0]").value("cat"))
                .andExpect(jsonPath("$.items[0].author.username").value("alice"))
                .andExpect(jsonPath("$.nextCursor").value("next"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void feedRejectsLimitTooSmall() throws Exception {
        mvc.perform(get("/api/v1/feed").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedRejectsLimitTooLarge() throws Exception {
        mvc.perform(get("/api/v1/feed").param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedReturnsBadRequestForInvalidCursor() throws Exception {
        when(query.getFeed(eq("garbage"), anyInt()))
                .thenThrow(new InvalidCursorException("Malformed cursor"));

        mvc.perform(get("/api/v1/feed").param("cursor", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    @Test
    void imageReturnsDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.getImage(id)).thenReturn(sampleImage(id));

        mvc.perform(get("/api/v1/images/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.imageUrl").value("https://minio/orig"))
                .andExpect(jsonPath("$.thumbnailUrl").value("https://minio/thumb"));
    }

    @Test
    void imageReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.getImage(id)).thenThrow(new ImageNotFoundException(id));

        mvc.perform(get("/api/v1/images/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void tagsReturnsList() throws Exception {
        when(query.listTags()).thenReturn(List.of(
                new TagCountResponse("cat", 5),
                new TagCountResponse("cute", 2)));

        mvc.perform(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tag").value("cat"))
                .andExpect(jsonPath("$[0].count").value(5))
                .andExpect(jsonPath("$[1].tag").value("cute"));
    }

    @Test
    void byTagReturnsPage() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.getImagesByTag(eq("cat"), isNull(), eq(20)))
                .thenReturn(new CursorPage<>(List.of(sampleImage(id)), null, false));

        mvc.perform(get("/api/v1/tags/cat/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void searchReturnsPage() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.search(eq("happy cat"), isNull(), eq(20)))
                .thenReturn(new CursorPage<>(List.of(sampleImage(id)), null, false));

        mvc.perform(get("/api/v1/search").param("q", "happy cat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].prompt").value("a cat"));
    }

    @Test
    void searchRequiresQuery() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userGenerationsReturnsPage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(query.getUserGenerations(eq(userId), isNull(), eq(20)))
                .thenReturn(new CursorPage<>(List.of(sampleImage(imageId)), null, false));

        mvc.perform(get("/api/v1/users/{id}/generations", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(imageId.toString()));
    }

    @Test
    void recommendationsWithUserId() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(recommendations.recommend(eq(userId), eq(20)))
                .thenReturn(List.of(sampleImage(imageId)));

        mvc.perform(get("/api/v1/recommendations").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(imageId.toString()));
    }

    @Test
    void recommendationsWithoutUserIdUsesFallback() throws Exception {
        UUID imageId = UUID.randomUUID();
        when(recommendations.recommend(isNull(), eq(20)))
                .thenReturn(List.of(sampleImage(imageId)));

        mvc.perform(get("/api/v1/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(imageId.toString()));
    }

    @Test
    void recommendationsRejectsLimitTooLarge() throws Exception {
        mvc.perform(get("/api/v1/recommendations").param("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recommendationsRejectsLimitTooSmall() throws Exception {
        mvc.perform(get("/api/v1/recommendations").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recommendationsRejectsInvalidUserIdHeader() throws Exception {
        mvc.perform(get("/api/v1/recommendations").header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}