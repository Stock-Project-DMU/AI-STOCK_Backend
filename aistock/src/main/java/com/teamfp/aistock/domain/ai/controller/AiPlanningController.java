package com.teamfp.aistock.domain.ai.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.ai.dto.request.AiChatRequest;
import com.teamfp.aistock.domain.ai.dto.request.AiPlanningSessionTitleRequest;
import com.teamfp.aistock.domain.ai.dto.response.AiChatResponse;
import com.teamfp.aistock.domain.ai.dto.response.AiPlanningSessionResponse;
import com.teamfp.aistock.domain.ai.service.AiPlanningService;
import com.teamfp.aistock.domain.user.service.UserService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/planning")
@RequiredArgsConstructor
public class AiPlanningController {

    private final AiPlanningService aiPlanningService;
    private final UserService userService;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AiPlanningSessionResponse>> createSession() {
        Long userId = SecurityUtil.getCurrentUserId();
        userService.requireCompletedSurvey(userId);
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

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<AiPlanningSessionResponse> renameSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody AiPlanningSessionTitleRequest request
    ) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(aiPlanningService.renameSession(userId, sessionId, request.title()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        Long userId = SecurityUtil.getCurrentUserId();
        aiPlanningService.deleteSession(userId, sessionId);
        return ApiResponse.success("상담 기록을 삭제했습니다.", null);
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
        userService.requireCompletedSurvey(userId);
        AiChatResponse response = aiPlanningService.sendMessage(userId, sessionId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}
