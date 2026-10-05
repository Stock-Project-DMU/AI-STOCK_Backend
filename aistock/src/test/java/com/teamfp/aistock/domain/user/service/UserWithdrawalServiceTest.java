package com.teamfp.aistock.domain.user.service;

import java.util.Optional;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
import com.teamfp.aistock.domain.user.dto.request.UserWithdrawalRequest;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {
    @Mock UserService userService;
    @Mock UserRepository userRepository;
    @Mock AccountRepository accountRepository;
    @Mock com.teamfp.aistock.domain.auth.service.InitialAdminService initialAdminService;
    @Mock com.teamfp.aistock.domain.admin.service.AuditLogService auditLogService;
    @InjectMocks UserWithdrawalService service;

    private void account(String loginId) {
        when(userRepository.findByUserIdAndIsActiveTrue(1L)).thenReturn(Optional.of(
                User.builder().loginId(loginId).email("member@example.com").role(Role.USER).isActive(true).build()));
    }

    @Test void socialAccountRejectsMissingOrWrongEmail() {
        account(null);
        for (String email : new String[]{null, "", "other@example.com"}) {
            assertThatThrownBy(() -> service.withdraw(1L, new UserWithdrawalRequest(null, email)))
                    .isInstanceOf(CustomException.class);
        }
        verifyNoInteractions(userService, accountRepository);
    }

    @Test void socialAccountAcceptsRegisteredEmailWithoutPassword() {
        account(null);
        // Stop at the cleanup boundary so this test cannot delete account data.
        var cleanupBoundary = new IllegalStateException("cleanup boundary");
        when(accountRepository.findAllByUserIdForUpdate(1L)).thenThrow(cleanupBoundary);
        assertThatThrownBy(() -> service.withdraw(1L, new UserWithdrawalRequest(null, "MEMBER@example.com")))
                .isSameAs(cleanupBoundary);
        verifyNoInteractions(userService);
    }

    @Test void regularAccountCannotUseEmailInsteadOfPassword() {
        account("member");
        assertThatThrownBy(() -> service.withdraw(1L, new UserWithdrawalRequest(null, "member@example.com")))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(userService, accountRepository);
    }

    @Test void regularAccountStillVerifiesPassword() {
        account("member");
        var cleanupBoundary = new IllegalStateException("cleanup boundary");
        when(accountRepository.findAllByUserIdForUpdate(1L)).thenThrow(cleanupBoundary);
        assertThatThrownBy(() -> service.withdraw(1L, new UserWithdrawalRequest("password", null)))
                .isSameAs(cleanupBoundary);
        verify(userService).verifyPassword(1L, new PasswordVerifyRequest("password"));
    }

    @Test void adminDisposeRejectsWrongAdminCodeAfterPasswordCheck() {
        User admin = User.builder().loginId("admin1").password("encoded").name("관리자").email("admin@example.com")
                .role(com.teamfp.aistock.domain.user.entity.Role.ADMIN).isActive(true).build();
        org.mockito.Mockito.when(userRepository.findByUserIdAndIsActiveTrue(1L)).thenReturn(java.util.Optional.of(admin));
        org.mockito.Mockito.doThrow(new CustomException(com.teamfp.aistock.global.exception.ErrorCode.INVALID_ADMIN_CODE))
                .when(initialAdminService).verifyAdminCode("user:1", "wrong");

        assertThatThrownBy(() -> service.withdraw(1L, new UserWithdrawalRequest("password", null, "wrong")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(com.teamfp.aistock.global.exception.ErrorCode.INVALID_ADMIN_CODE);
        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never()).findAllByUserIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }
}
