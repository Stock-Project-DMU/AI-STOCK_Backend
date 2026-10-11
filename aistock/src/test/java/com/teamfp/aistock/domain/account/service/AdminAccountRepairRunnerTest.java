package com.teamfp.aistock.domain.account.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import static org.mockito.Mockito.*;

class AdminAccountRepairRunnerTest {
    @Test
    void repairsOnlyMissingAdminAccountsAndDoesNotRepeatCompletedRepairs() {
        UserRepository users = mock(UserRepository.class);
        AccountService accounts = mock(AccountService.class);
        when(users.findActiveUserIdsWithoutAccount(Role.ADMIN)).thenReturn(List.of(4L, 5L), List.of());
        AdminAccountRepairRunner runner = new AdminAccountRepairRunner(users, accounts);
        runner.run(null);
        runner.run(null);
        verify(accounts).createAccount(4L, new CreateAccountRequest("기본 계좌"));
        verify(accounts).createAccount(5L, new CreateAccountRequest("기본 계좌"));
        verifyNoMoreInteractions(accounts);
    }
}
