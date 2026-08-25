package com.teamfp.aistock.domain.ai.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.ai.dto.request.AiChatRequest;
import com.teamfp.aistock.domain.ai.dto.response.AiChatResponse;
import com.teamfp.aistock.domain.ai.dto.response.AiPlanningSessionResponse;
import com.teamfp.aistock.domain.ai.service.AiPlanningService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/planning")
@RequiredArgsConstructor
public class AiPlanningController {

    private final AiPlanningService aiPlanningService;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AiPlanningSessionResponse>> createSession() {
        Long userId = SecurityUtil.getCurrentUserId();
        AiPlanningSessionResponse response = aiPlanningService.createSession(userId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("상담 세션이 생성되었습니다.", response));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AiPlanningSessionResponse>> getMySessions() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(aiPlanningService.getMySessions(userId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<AiChatResponse>> getMessages(@PathVariable Long sessionId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(aiPlanningService.getMessages(userId, sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<AiChatResponse>> sendMessage(
            @PathVariable Long sessionId,
            @Valid @RequestBody AiChatRequest request
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        AiChatResponse response = aiPlanningService.sendMessage(userId, sessionId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}
