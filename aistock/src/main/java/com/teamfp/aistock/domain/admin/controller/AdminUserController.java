package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminUserStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserListResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminWithdrawnUserResponse;
import com.teamfp.aistock.domain.admin.service.AdminUserService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 사용자 목록·상세 조회 및 활성상태 변경 API. SecurityConfig에서
 * "/api/admin/**"는 hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    // query(회원번호/아이디/이름/이메일 통합검색), status, role은 전부 선택 파라미터다.
    // 기본 정렬은 createdAt 내림차순(3.2 요구사항) — 클라이언트가 sort를 직접 지정하면
    // PageableDefault 값 대신 그 값이 우선 적용된다(Spring Data Web 기본 동작).
    @GetMapping
    public ApiResponse<Page<AdminUserListResponse>> getUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Role role,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminUserService.getUsers(query, status, role, pageable));
    }

    // 화면과 동일한 검색·필터 조건(query/status/role)을 그대로 받는다(6.2 요구사항). sort는
    // 목록 API와 동일하게 createdAt 내림차순 기본값을 쓴다.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        byte[] csv = adminUserService.exportUsersCsv(query, status, role, parseSort(sort));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=admin-users.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", 2);
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, parts[0]);
    }

    // 탈퇴 회원 조회(5.4 옵션2, 정책 미확정 상태에서 잠정 구현 — AdminUserService.getWithdrawnUsers()
    // Javadoc 참고). 기본 정렬은 최근 탈퇴순(deletedAt 내림차순).
    @GetMapping("/withdrawn")
    public ApiResponse<Page<AdminWithdrawnUserResponse>> getWithdrawnUsers(
            @PageableDefault(size = 20, sort = "deletedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(adminUserService.getWithdrawnUsers(pageable));
    }

    @GetMapping("/{userId}")
    public ApiResponse<AdminUserDetailResponse> getUserDetail(@PathVariable Long userId) {
        return ApiResponse.success(adminUserService.getUserDetail(userId));
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserDetailResponse> updateUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserStatusRequest request
    ) {
        Long adminUserId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("사용자 상태가 변경되었습니다.", adminUserService.updateUserStatus(adminUserId, userId, request));
    }
}
