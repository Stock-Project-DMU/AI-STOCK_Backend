package com.teamfp.aistock.domain.auth.service;

import com.teamfp.aistock.domain.auth.dto.request.LoginRequest;
import com.teamfp.aistock.domain.auth.dto.request.OAuthLoginRequest;
import com.teamfp.aistock.domain.auth.dto.response.LoginResponse;
import com.teamfp.aistock.domain.auth.dto.response.TokenResponse;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.SocialAccount;
import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.SocialAccountRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisTokenService;
import com.teamfp.aistock.global.security.JwtProvider;
import com.teamfp.aistock.infra.oauth.OAuthClient;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RedisTokenService redisTokenService;
    private final Map<SocialProvider, OAuthClient> oauthClients;

    public AuthService(
            UserRepository userRepository,
            SocialAccountRepository socialAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtProvider jwtProvider,
            RedisTokenService redisTokenService,
            List<OAuthClient> clientList
    ) {
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.redisTokenService = redisTokenService;
        this.oauthClients = clientList.stream()
                .collect(Collectors.toMap(OAuthClient::getProvider, Function.identity()));
    }

    // processSocialLogin()의 @Transactional(REQUIRES_NEW)은 Spring AOP 프록시를 거쳐야만 실제로
    // 새 트랜잭션을 연다. socialLogin()이 같은 클래스 안에서 this.processSocialLogin(...)을 그냥
    // 호출하면(self-invocation) 프록시를 우회해 REQUIRES_NEW가 무시되고 호출부의 트랜잭션을 그대로
    // 이어받는다 — 그러면 uq_provider 충돌로 flush가 실패해 손상된 Hibernate Session을 재시도
    // 시점에도 그대로 재사용하게 되어 동시성 재시도가 무의미해진다. OrderExecutionService와 동일한
    // 패턴으로, @Lazy 필드 주입으로 받은 프록시(자기 자신)를 통해 self.processSocialLogin(...)으로
    // 호출해야 한다.
    @Autowired
    @Lazy
    private AuthService self;

    /**
     * 일반 로그인
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 탈퇴 회원 필터링 (isActive 검사)
        if (!user.isActive()) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 정지된 계정 차단 (UserStatus 검증)
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.USER_SUSPENDED);
        }

        return generateLoginResponse(user);
    }

    /**
     * 소셜 로그인 (카카오, 네이버, 구글 통합)
     * [방식 A] 동일 이메일이 이미 존재할 경우, 해당 계정에 소셜을 연동하여 하나의 통합 유저로 관리
     *
     * 이 메서드 자체는 트랜잭션을 열지 않는다(클래스 기본값인 readOnly 트랜잭션만 적용) — 외부
     * OAuth API 호출(client.getUserInfo)을 커넥션을 물고 있는 채로 기다리지 않기 위함이다. 실제
     * 조회/가입/연동은 self 프록시를 통해 processSocialLogin()(REQUIRES_NEW)에 위임한다.
     */
    public LoginResponse socialLogin(OAuthLoginRequest request) {
        OAuthClient client = Optional.ofNullable(oauthClients.get(request.getProvider()))
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));

        // 소셜 유저 정보 가져오기
        SocialUserDto userInfo = client.getUserInfo(request.getCode());

        try {
            return self.processSocialLogin(request.getProvider(), userInfo);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 uq_provider 제약 위반이 발생한 경우 1회 재시도한다.
            // processSocialLogin()이 REQUIRES_NEW라 방금 실패한 시도의 트랜잭션(및 flush 실패로
            // 손상된 Hibernate Session)은 이미 롤백되어 정리된 상태이므로, self 프록시를 통해
            // 완전히 새 트랜잭션/세션으로 다시 시도하면 안전하다.
            log.warn("소셜 로그인 중복 가입 경합 발생 - 새 트랜잭션으로 재시도합니다.");
            return self.processSocialLogin(request.getProvider(), userInfo);
        }
    }

    /**
     * 소셜 로그인의 조회/가입/연동 처리. socialLogin()이 self 프록시를 통해서만 호출해야 하며,
     * 매번 독립된 새 트랜잭션(REQUIRES_NEW)에서 실행되어야 동시 가입 경합 시의 재시도가 안전하다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginResponse processSocialLogin(SocialProvider provider, SocialUserDto userInfo) {
        // 이미 해당 소셜 연동(SocialAccount)이 존재하는지 조회
        Optional<SocialAccount> socialAccountOpt = socialAccountRepository
                .findByProviderAndProviderId(provider, userInfo.getProviderId());

        User user;
        if (socialAccountOpt.isPresent()) {
            user = socialAccountOpt.get().getUser();

            // 탈퇴 여부 확인
            if (!user.isActive()) {
                throw new CustomException(ErrorCode.USER_NOT_FOUND);
            }
        } else {
            // [방식 A] 동일한 이메일로 가입한 다른 일반/소셜 유저가 있는지 확인
            // 이메일이 null이 아닌 경우에만 조회 (카카오/네이버 이메일 거부 대응)
            Optional<User> existingUserOpt = userInfo.getEmail() != null
                    ? userRepository.findByEmail(userInfo.getEmail())
                    : Optional.empty();

            if (existingUserOpt.isPresent()) {
                user = existingUserOpt.get();
                // 정지/탈퇴 여부 검증
                if (!user.isActive()) {
                    throw new CustomException(ErrorCode.USER_NOT_FOUND);
                }
                log.info("동일한 이메일[{}]을 사용하는 기존 유저가 존재하여 소셜 연동만 수행합니다.", userInfo.getEmail());
            } else {
                // 아예 처음 가입하는 유저라면 신규 생성
                user = User.builder()
                        .email(userInfo.getEmail())
                        .name(userInfo.getName())
                        .role(Role.USER)
                        .isActive(true)
                        .build();
                userRepository.save(user);
                log.info("신규 소셜 회원[{}] 가입 처리를 수행합니다.", userInfo.getEmail());
            }

            // 소셜 연동 정보 추가 저장
            SocialAccount socialAccount = SocialAccount.builder()
                    .user(user)
                    .provider(provider)
                    .providerId(userInfo.getProviderId())
                    .build();
            socialAccountRepository.save(socialAccount);
        }

        // 정지 상태 검증
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.USER_SUSPENDED);
        }

        return generateLoginResponse(user);
    }

    /**
     * 토큰 재발급 (Refresh Token RTR 전략)
     */
    public TokenResponse refresh(String authHeader) {
        String refreshToken = jwtProvider.extractBearerToken(authHeader);
        if (refreshToken == null) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        // validateToken 대신 getUserId를 직접 호출하여 parseClaims 내부의 TOKEN_EXPIRED 예외를 그대로 전파한다.
        Long userId = jwtProvider.getUserId(refreshToken);

        // Redis에 저장된 Refresh Token과 일치하는지 검증
        if (!redisTokenService.isRefreshTokenValid(userId, refreshToken)) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        User user = userRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 정지된 계정 차단
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.USER_SUSPENDED);
        }

        // 신규 Access Token 및 Refresh Token 생성 (RTR 전략)
        String newAccessToken = jwtProvider.createAccessToken(user.getUserId(), user.getRole());
        String newRefreshToken = jwtProvider.createRefreshToken(user.getUserId());

        // Redis 갱신
        redisTokenService.saveRefreshToken(user.getUserId(), newRefreshToken);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    private LoginResponse generateLoginResponse(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getUserId(), user.getRole());
        String refreshToken = jwtProvider.createRefreshToken(user.getUserId());

        // Refresh Token을 Redis에 저장
        redisTokenService.saveRefreshToken(user.getUserId(), refreshToken);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .build();
    }
}
