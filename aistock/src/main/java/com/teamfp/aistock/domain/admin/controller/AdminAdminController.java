package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserListResponse;
import com.teamfp.aistock.domain.admin.service.AdminUserService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 관리자 계정 목록·상세 조회 및 상태 변경 API(ADMIN_API_BACKEND_HANDOFF.md 5.1).
 * 관리자도 users 테이블의 User(role=ADMIN)일 뿐이라 AdminUserService의 검색·상태변경 로직을
 * role=ADMIN 조건으로 그대로 재사용한다 — 자기 자신 정지 금지, 마지막 활성 관리자 정지 금지
 * 가드(AdminUserService.validateSuspendable())도 동일하게 적용된다.
 *
 * POST /api/admin/admins(신규 관리자 생성)는 handoff 문서 9번 "구현 전 결정이 필요한 정책" 6번
 * (관리자를 기존 회원 승격 방식으로 만들지, 별도 생성 방식으로 만들지)이 아직 정해지지 않았지만,
 * 우선 "별도 생성 방식"으로 구현해뒀다 — 승격 방식으로 정책이 정해지면
 * AdminUserService.createAdmin() 자체를 제거해야 한다.
 */
@RestController
@RequestMapping("/api/admin/admins")
@RequiredArgsConstructor
public class AdminAdminController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ApiResponse<Page<AdminUserListResponse>> getAdmins(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminUserService.getUsers(query, status, Role.ADMIN, pageable));
    }

    @GetMapping("/{adminId}")
    public ApiResponse<AdminUserDetailResponse> getAdminDetail(@PathVariable Long adminId) {
        return ApiResponse.success(adminUserService.getAdminDetail(adminId));
    }

    @PostMapping
    public ApiResponse<AdminUserListResponse> createAdmin(@Valid @RequestBody AdminCreateRequest request) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("관리자 계정이 생성되었습니다.", adminUserService.createAdmin(adminUserId, request));
    }

    @PatchMapping("/{adminId}/status")
    public ApiResponse<AdminUserDetailResponse> updateAdminStatus(
            @PathVariable Long adminId,
            @Valid @RequestBody AdminUserStatusRequest request
    ) {
        Long currentAdminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("관리자 상태가 변경되었습니다.", adminUserService.updateAdminStatus(currentAdminUserId, adminId, request));
    }
}
