package com.teamfp.aistock.domain.admin.service;

import com.teamfp.aistock.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class SuspensionExpiryJob {
    private final UserRepository userRepository;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void releaseExpired() {
        userRepository.releaseExpiredSuspensions(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    }
}
