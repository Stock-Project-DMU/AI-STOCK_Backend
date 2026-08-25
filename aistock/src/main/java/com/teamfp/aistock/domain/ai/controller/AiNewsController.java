package com.teamfp.aistock.domain.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.ai.dto.request.NewsBriefingSettingRequest;
import com.teamfp.aistock.domain.ai.dto.response.NewsBriefingResponse;
import com.teamfp.aistock.domain.ai.dto.response.NewsBriefingSettingResponse;
import com.teamfp.aistock.domain.ai.dto.response.NewsOutletResponse;
import com.teamfp.aistock.domain.ai.service.AiNewsService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * feature/ai-news(맞춤형 뉴스 브리핑) — 대화형 API가 아니다. 사용자는 언론사만 설정해두고,
 * 실제 브리핑은 AiNewsService.generateDailyBriefings()가 매일 정해진 시각에 만들어 둔다.
 */
@RestController
@RequestMapping("/api/ai/news")
@RequiredArgsConstructor
public class AiNewsController {

    private final AiNewsService aiNewsService;

    @GetMapping("/outlets")
    public ApiResponse<List<NewsOutletResponse>> getSelectableOutlets() {
        return ApiResponse.success(aiNewsService.getSelectableOutlets());
    }

    @GetMapping("/settings")
    public ApiResponse<NewsBriefingSettingResponse> getMySetting() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(aiNewsService.getMySetting(userId));
    }

    @PutMapping("/settings")
    public ApiResponse<NewsBriefingSettingResponse> updateMySetting(@Valid @RequestBody NewsBriefingSettingRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("언론사 설정이 저장되었습니다.", aiNewsService.updateMySetting(userId, request.outletDomain()));
    }

    @GetMapping("/briefings/today")
    public ApiResponse<NewsBriefingResponse> getTodayBriefing() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(aiNewsService.getTodayBriefing(userId));
    }
}
