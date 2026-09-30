package com.teamfp.aistock.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.user.entity.SocialAccount;
import com.teamfp.aistock.domain.user.entity.SocialProvider;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderId(SocialProvider provider, String providerId);

    @Modifying
    @Query("delete from SocialAccount s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
