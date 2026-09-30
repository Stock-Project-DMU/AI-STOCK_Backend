package com.teamfp.aistock.domain.ai.dto.response;

import java.util.List;

public record NewsChatSessionsResponse(Long currentSessionId, List<NewsChatSessionResponse> sessions) {}
