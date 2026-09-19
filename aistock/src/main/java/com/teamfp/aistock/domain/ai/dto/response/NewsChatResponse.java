package com.teamfp.aistock.domain.ai.dto.response;

import java.util.List;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse.NaverNewsResult;

public record NewsChatResponse(String content, List<NaverNewsResult> sources, String searchedAt) {}
