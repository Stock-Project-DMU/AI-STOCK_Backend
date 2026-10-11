package com.teamfp.aistock.domain.account.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 기존 관리자 생성 경로에서 누락된 계좌만 표준 계좌 개설·초기 지급 절차로 복구한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountRepairRunner implements ApplicationRunner {
    private final UserRepository userRepository;
    private final AccountService accountService;

    @Override
    public void run(ApplicationArguments args) {
        for (Long userId : userRepository.findActiveUserIdsWithoutAccount(Role.ADMIN)) {
            accountService.createAccount(userId, new CreateAccountRequest("기본 계좌"));
            log.info("관리자 누락 계좌 복구 완료: userId={}", userId);
        }
    }
}
