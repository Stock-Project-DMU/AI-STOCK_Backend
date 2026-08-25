package com.teamfp.aistock.global.stomp;

import java.security.Principal;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisOnlineStatusService;
import com.teamfp.aistock.global.security.CustomUserDetails;
import com.teamfp.aistock.global.security.CustomUserDetailsService;
import com.teamfp.aistock.global.security.JwtProvider;

import lombok.RequiredArgsConstructor;

/**
 * STOMP CONNECT 시 JWT를 검증해 Authentication을 세션에 붙이는 동시에, 관리자 대시보드의
 * "온라인 사용자 수"(admin:online:users)를 CONNECT/세션 종료 시점에 갱신한다
 * (CLAUDE.md 8번, NAMING.md 7번/5번 StompAuthInterceptor 항목 참고).
 */
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final RedisOnlineStatusService redisOnlineStatusService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = resolveToken(accessor);

        if (!StringUtils.hasText(token) || !jwtProvider.validateToken(token)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        Long userId = jwtProvider.getUserId(token);
        CustomUserDetails userDetails = customUserDetailsService.loadUserByUsername(String.valueOf(userId));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        accessor.setUser(authentication);
        redisOnlineStatusService.addOnline(userId);

        return message;
    }

    /**
     * 세션 종료(정상적인 DISCONNECT 프레임이든, 네트워크 끊김·브라우저 강제 종료 같은 비정상
     * 종료든 상관없이) 시 호출된다 — DISCONNECT 처리를 preSend()의 STOMP DISCONNECT 커맨드
     * 분기가 아니라 이 리스너 하나로만 처리한다.
     *
     * 왜 preSend(DISCONNECT)를 따로 두지 않는가: 정상 종료라도 클라이언트가 DISCONNECT
     * 프레임을 보낸 뒤 소켓이 실제로 닫히면 이 SessionDisconnectEvent도 함께 발행된다. 예전
     * Set 기반 구현(addOnline/removeOnline이 SADD/SREM)에서는 두 경로 모두에서
     * removeOnline()을 불러도 SREM이 멱등해 무해했지만, 지금은 admin:online:users가
     * "유저별 활성 세션 수" Hash라 세션 하나가 끝났는데 removeOnline()이 두 번 호출되면
     * 카운트가 실제보다 1 더 줄어든다 — 그 유저의 다른 탭(세션)이 여전히 연결돼 있어도 조기에
     * 오프라인으로 잘못 표시되는 문제가 생긴다(코드리뷰 반영). SessionDisconnectEvent는 세션이
     * 어떻게 끝나든 정확히 한 번만 발행되므로, 세션 종료를 세는 유일한 지점으로 이거 하나만
     * 남긴다.
     *
     * event.getMessage()의 accessor가 아니라 event.getUser()를 직접 쓴다 — 비정상 종료 시
     * 이벤트에 실리는 메시지는 완전한 STOMP 프레임이 아닐 수 있어 그 안의 헤더로 유저를 복원할
     * 수 있다는 보장이 없는 반면, SessionDisconnectEvent는 세션 종료 시점에 스프링이 세션에
     * 붙어있던 Principal을 생성자 인자로 직접 실어준다(AbstractSubProtocolEvent.getUser()).
     */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        extractUserId(event.getUser()).ifPresent(redisOnlineStatusService::removeOnline);
    }

    private Optional<Long> extractUserId(Principal principal) {
        if (!(principal instanceof UsernamePasswordAuthenticationToken authentication)) {
            return Optional.empty();
        }
        if (!(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return Optional.empty();
        }
        return Optional.of(userDetails.getUserId());
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        return jwtProvider.extractBearerToken(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
    }
}
