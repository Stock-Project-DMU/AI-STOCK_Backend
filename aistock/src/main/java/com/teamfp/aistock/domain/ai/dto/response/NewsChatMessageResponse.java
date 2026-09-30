package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse.NaverNewsResult;

public record NewsChatMessageResponse(Long messageId, String role, String content,
        List<NaverNewsResult> sources, String searchedAt, LocalDateTime createdAt) {}
