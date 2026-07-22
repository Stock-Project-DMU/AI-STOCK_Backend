package com.teamfp.aistock.domain.stock.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RecentViewedRequest(

        @NotBlank(message = "종목 코드는 필수입니다.")
        String stockCode
) {
}
