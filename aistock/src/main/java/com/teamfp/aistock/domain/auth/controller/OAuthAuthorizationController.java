package com.teamfp.aistock.domain.auth.controller;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.OAuthProviderClient;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@RestController @RequestMapping("/api/auth/oauth") @RequiredArgsConstructor
public class OAuthAuthorizationController {
    private final OAuthProviderClient oauthProviderClient;
    private static final SecureRandom RANDOM = new SecureRandom();

    @GetMapping("/{provider}/authorize")
    public ApiResponse<Map<String, String>> authorize(@PathVariable SocialProvider provider, HttpSession session) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String url = oauthProviderClient.authorizationUrl(provider, state);
        synchronized (session) {
            session.setAttribute("oauth." + provider, state);
            session.setAttribute("oauth." + provider + ".expires", System.currentTimeMillis() + 600_000);
        }
        return ApiResponse.success(Map.of("url", url, "state", state));
    }

    public static void consumeState(HttpSession session, SocialProvider provider, String state) {
        if (session == null || state == null) throw new CustomException(ErrorCode.INVALID_TOKEN);
        synchronized (session) {
            Object stored = session.getAttribute("oauth." + provider);
            Object expires = session.getAttribute("oauth." + provider + ".expires");
            session.removeAttribute("oauth." + provider);
            session.removeAttribute("oauth." + provider + ".expires");
            if (!state.equals(stored) || !(expires instanceof Long deadline) || deadline < System.currentTimeMillis())
                throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
    }
}
