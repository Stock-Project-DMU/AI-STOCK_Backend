package com.teamfp.aistock.global.stomp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisOnlineStatusService;
import com.teamfp.aistock.global.security.CustomUserDetails;
import com.teamfp.aistock.global.security.CustomUserDetailsService;
import com.teamfp.aistock.global.security.JwtProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/admin-dashboard — StompAuthInterceptor의 온라인 사용자 추적 단위 테스트.
 * 세션 종료 처리는 SessionDisconnectEvent 하나로만 이뤄진다 — preSend()는 더 이상 STOMP
 * DISCONNECT 커맨드를 별도로 처리하지 않는다(코드리뷰 반영: 같은 세션 종료에 대해
 * removeOnline()이 두 번 불리면 admin:online:users의 세션 카운트가 실제보다 더 줄어드는
 * 문제가 있어, 세션 종료를 세는 지점을 SessionDisconnectEvent 하나로 통일했다).
 */
@ExtendWith(MockitoExtension.class)
class StompAuthInterceptorTest {

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private CustomUserDetailsService customUserDetailsService;

    @Mock
    private RedisOnlineStatusService redisOnlineStatusService;

    @InjectMocks
    private StompAuthInterceptor stompAuthInterceptor;

    private static final Long USER_ID = 1L;
    private static final String TOKEN = "valid-token";

    private CustomUserDetails userDetails;

    @BeforeEach
    void setUp() {
        userDetails = new CustomUserDetails(USER_ID, Role.USER);
    }

    /**
     * StompHeaderAccessor.getMessageHeaders()는 leaveMutable(true)를 미리 걸어두지 않으면
     * 호출 즉시 accessor를 얼려버린다(Spring 내부 규약) — 이후 preSend()가 그 accessor에
     * setUser()로 인증 정보를 붙이려 하면 "Already immutable" IllegalStateException이 난다.
     * 실제 STOMP 파이프라인도 인터셉터 체인을 여러 단계 거치는 동안 헤더를 계속 고쳐 써야 해서
     * leaveMutable(true)로 두는 것과 같은 이유다.
     */
    private Message<byte[]> buildMessage(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> connectMessage(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (token != null) {
            accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return buildMessage(accessor);
    }

    private Message<byte[]> disconnectMessage(UsernamePasswordAuthenticationToken authentication) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        if (authentication != null) {
            // 실제 서버에서는 StompSubProtocolHandler가 CONNECT 때 세션에 저장해둔 Principal을
            // DISCONNECT를 포함한 이후 모든 프레임에 자동으로 다시 실어준다. 단위 테스트에서는
            // 이 인프라 동작을 재현할 수 없으니 그 결과(accessor.getUser())만 직접 주입한다.
            accessor.setUser(authentication);
        }
        return buildMessage(accessor);
    }

    @Test
    @DisplayName("CONNECT — 유효한 토큰이면 인증을 세션에 붙이고 온라인 목록에 추가한다")
    void connect_validToken_addsOnline() {
        when(jwtProvider.extractBearerToken("Bearer " + TOKEN)).thenReturn(TOKEN);
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(TOKEN)).thenReturn(USER_ID);
        when(customUserDetailsService.loadUserByUsername(String.valueOf(USER_ID))).thenReturn(userDetails);

        stompAuthInterceptor.preSend(connectMessage(TOKEN), null);

        verify(redisOnlineStatusService).addOnline(USER_ID);
    }

    @Test
    @DisplayName("CONNECT — 토큰이 없거나 유효하지 않으면 INVALID_TOKEN 예외를 던지고 온라인 처리를 하지 않는다")
    void connect_invalidToken_throwsAndSkipsOnline() {
        when(jwtProvider.extractBearerToken(null)).thenReturn(null);

        assertThatThrownBy(() -> stompAuthInterceptor.preSend(connectMessage(null), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(redisOnlineStatusService, never()).addOnline(anyLong());
    }

    @Test
    @DisplayName("DISCONNECT — preSend()는 더 이상 STOMP DISCONNECT 커맨드를 처리하지 않는다(SessionDisconnectEvent로 일원화)")
    void preSend_disconnectCommand_isIgnored() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        stompAuthInterceptor.preSend(disconnectMessage(authentication), null);

        verify(redisOnlineStatusService, never()).removeOnline(anyLong());
    }

    @Test
    @DisplayName("onSessionDisconnect — DISCONNECT 프레임 없이 비정상 종료돼도 event.getUser() 기준으로 온라인 목록에서 제거한다")
    void onSessionDisconnect_removesOnline() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SessionDisconnectEvent event = new SessionDisconnectEvent(
                this, disconnectMessage(null), "session-1", CloseStatus.NORMAL, authentication);

        stompAuthInterceptor.onSessionDisconnect(event);

        verify(redisOnlineStatusService).removeOnline(USER_ID);
    }

    @Test
    @DisplayName("onSessionDisconnect — 인증 정보가 없는 세션 종료는 무시한다")
    void onSessionDisconnect_withoutUser_doesNothing() {
        SessionDisconnectEvent event =
                new SessionDisconnectEvent(this, disconnectMessage(null), "session-1", CloseStatus.NORMAL);

        stompAuthInterceptor.onSessionDisconnect(event);

        verify(redisOnlineStatusService, never()).removeOnline(anyLong());
    }
}
