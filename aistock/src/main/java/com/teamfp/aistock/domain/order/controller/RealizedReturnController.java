package com.teamfp.aistock.domain.order.controller;
import com.teamfp.aistock.domain.order.dto.response.RealizedReturnResponse;
import com.teamfp.aistock.domain.order.service.RealizedReturnService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/accounts/{accountId}/returns") @RequiredArgsConstructor
public class RealizedReturnController {
    private final RealizedReturnService realizedReturnService;
    @GetMapping public ApiResponse<List<RealizedReturnResponse>> getReturns(@PathVariable Long accountId) {
        return ApiResponse.success(realizedReturnService.getReturns(SecurityUtil.getCurrentUserId(), accountId));
    }
}
