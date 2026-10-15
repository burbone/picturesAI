package com.picturesAI.user_service.keycloak;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

@Component
public class OAuth2KeycloakTokenSupplier implements KeycloakTokenSupplier {

    private static final long TOKEN_SAFETY_MARGIN_SECONDS = 10;

    private final ClientRegistrationRepository registrations;
    private final RestClient restClient;
    private final String registrationId;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public OAuth2KeycloakTokenSupplier(ClientRegistrationRepository registrations,
                                       RestClient.Builder builder,
                                       KeycloakProperties properties) {
        this.registrations = registrations;
        this.restClient = builder.build();
        this.registrationId = properties.getAdmin().getRegistrationId();
    }

    @Override
    public String token(boolean forceRefresh) {
        Instant now = Instant.now();
        if (!forceRefresh && cachedToken != null && now.isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        synchronized (this) {
            now = Instant.now();
            if (!forceRefresh && cachedToken != null && now.isBefore(tokenExpiresAt)) {
                return cachedToken;
            }
            return fetch();
        }
    }

    @SuppressWarnings("unchecked")
    private String fetch() {
        ClientRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration == null) {
            throw new IllegalStateException(
                    "ClientRegistration not found: " + registrationId);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", registration.getClientId());
        form.add("client_secret", registration.getClientSecret());

        Map<String, Object> body = restClient.post()
                .uri(registration.getProviderDetails().getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("Keycloak token response missing access_token");
        }
        String token = body.get("access_token").toString();
        long expiresIn = ((Number) body.getOrDefault("expires_in", 60)).longValue();
        cachedToken = token;
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn - TOKEN_SAFETY_MARGIN_SECONDS));
        return token;
    }
}