package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.user.entity.SocialAccount;
import com.teamfp.aistock.domain.user.entity.SocialProvider;

/**
 * 관리자 회원 상세의 소셜 로그인 연동 한 건(feat/admin-improvements) — 어떤 소셜(KAKAO/NAVER/GOOGLE)로 언제 연동했는지.
 * 소셜 쪽 식별자(providerId)는 화면에 필요 없고 노출할 이유도 없어 내려주지 않는다.
 */
public record AdminSocialAccountResponse(
        SocialProvider provider,
        LocalDateTime linkedAt
) {

    public static AdminSocialAccountResponse from(SocialAccount socialAccount) {
        return new AdminSocialAccountResponse(socialAccount.getProvider(), socialAccount.getCreatedAt());
    }
}
