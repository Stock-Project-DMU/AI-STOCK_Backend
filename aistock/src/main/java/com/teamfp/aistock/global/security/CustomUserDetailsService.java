package com.teamfp.aistock.global.security;

import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public CustomUserDetails loadUserByUsername(String userId) {
        // 탈퇴(is_active=0)한 사용자는 매 요청마다 이 조회에서 걸러지므로,
        // 이미 발급된 Access/Refresh Token이 만료 전이더라도 탈퇴 이후 인증이 통과되지 않는다.
        User user = userRepository.findByUserIdAndIsActiveTrue(Long.valueOf(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return new CustomUserDetails(user.getUserId(), user.getRole());
    }
}
