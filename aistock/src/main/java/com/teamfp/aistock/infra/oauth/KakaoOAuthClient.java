package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.KakaoUserDto;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient implements OAuthClient {

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public SocialUserDto getUserInfo(String code) {
        log.info("카카오 소셜 로그인 유저 정보 획득 시작 - code: {}", code);
        
        // TODO: 실제 카카오 토큰 발급 및 사용자 프로필 API 조회 로직 구현부
        // RestTemplate 또는 WebClient를 사용해 아래 구조로 조회하여 파싱합니다.
        // ResponseEntity<KakaoUserDto> response = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, KakaoUserDto.class);
        // return response.getBody().toSocialUserDto();

        // 현재 단계에서는 Mock 데이터를 생성해 실제 타입 안정적인 KakaoUserDto 구조를 테스트합니다.
        KakaoUserDto dummyKakaoInfo = createDummyKakaoInfo(code);
        return dummyKakaoInfo.toSocialUserDto();
    }

    private KakaoUserDto createDummyKakaoInfo(String code) {
        // 실제 KakaoUserDto가 Jackson에 의해 JSON 바인딩된 후의 객체 구조 시뮬레이션
        return KakaoUserDto.builder()
                .id((long) Math.abs(code.hashCode()))
                .kakaoAccount(KakaoUserDto.KakaoAccount.builder()
                        .email("kakao_" + Math.abs(code.hashCode()) + "@kakao.com")
                        .profile(KakaoUserDto.KakaoAccount.Profile.builder()
                                .nickname("카카오회원_" + code.substring(0, Math.min(code.length(), 5)))
                                .build())
                        .build())
                .build();
    }
}
