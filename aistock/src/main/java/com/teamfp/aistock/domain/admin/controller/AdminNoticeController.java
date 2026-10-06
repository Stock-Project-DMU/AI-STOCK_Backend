package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminNoticePopupRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.response.AdminNoticeResponse;
import com.teamfp.aistock.domain.admin.service.AdminNoticeService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 "알림 관리"(feat/admin-improvements) — 보낸 공지 목록·상세, 팝업 기한 변경·바로 종료, 삭제.
 * 목록은 다른 관리자 목록과 같은 검색(query/field/matchType), popup 필터(true=팝업 공지만, false=일반 공지만),
 * sortBy(정렬 선택칸)와 sortColumn + direction(열 제목 클릭, 있으면 우선)을 받는다.
 */
@RestController
@RequestMapping("/api/admin/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final AdminNoticeService adminNoticeService;

    @GetMapping
    public ApiResponse<Page<AdminNoticeResponse>> getNotices(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "ALL") AdminNoticeSearchField field,
            @RequestParam(defaultValue = "CONTAINS") AdminSearchMatchType matchType,
            @RequestParam(required = false) Boolean popup,
            @RequestParam(defaultValue = "LATEST") AdminNoticeSortType sortBy,
            @RequestParam(required = false) AdminNoticeSortColumn sortColumn,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminNoticeService.getNotices(AdminSearchConditionDto.of(query, field, matchType), popup,
                AdminSortSupport.notices(pageable, sortBy, sortColumn, direction)));
    }

    @GetMapping("/{noticeId}")
    public ApiResponse<AdminNoticeResponse> getNoticeDetail(@PathVariable Long noticeId) {
        return ApiResponse.success(adminNoticeService.getNoticeDetail(noticeId));
    }

    @PatchMapping("/{noticeId}/popup")
    public ApiResponse<AdminNoticeResponse> changePopupEndDate(@PathVariable Long noticeId,
            @Valid @RequestBody AdminNoticePopupRequest request) {
        return ApiResponse.success("팝업 기한을 변경했습니다.",
                adminNoticeService.changePopupEndDate(SecurityUtil.getCurrentUserId(), noticeId, request.popupEndDate()));
    }

    @PatchMapping("/{noticeId}/popup-end")
    public ApiResponse<AdminNoticeResponse> endPopup(@PathVariable Long noticeId) {
        return ApiResponse.success("팝업을 종료했습니다.", adminNoticeService.endPopup(SecurityUtil.getCurrentUserId(), noticeId));
    }

    @DeleteMapping("/{noticeId}")
    public ApiResponse<Void> deleteNotice(@PathVariable Long noticeId) {
        adminNoticeService.deleteNotice(SecurityUtil.getCurrentUserId(), noticeId);
        return ApiResponse.success("공지를 삭제했습니다.", null);
    }
}
