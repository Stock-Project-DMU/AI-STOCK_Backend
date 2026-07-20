package com.teamfp.aistock.global.exception;

import java.util.List;

import com.teamfp.aistock.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business Exception: [{}] {}", errorCode.getCode(), errorCode.getMessage());
        
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        // getFieldErrors()는 필드 단위 검증 실패만 담는다. 클래스 레벨 검증(@AssertTrue 등)만
        // 실패한 경우 필드 에러가 하나도 없을 수 있으므로, get(0) 대신 안전하게 첫 번째 요소를 꺼낸다.
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String message = fieldErrors.isEmpty()
                ? ErrorCode.INVALID_INPUT.getMessage()
                : fieldErrors.get(0).getDefaultMessage();
        log.warn("Validation Exception: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    // Account.version(@Version) 낙관적 락 충돌 — 동시에 같은 계좌로 주문/잔고 변경이 몰려
    // 한쪽이 먼저 커밋하면 뒤에 커밋하려는 트랜잭션이 이 예외를 던진다. 그대로 두면
    // handleException(Exception)이 잡아 의미 없는 500으로 나가버리므로, 이미 등록되어 있는
    // ErrorCode.OPTIMISTIC_LOCK_CONFLICT(409)로 변환해 "다시 시도해 달라"고 명확히 안내한다.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailureException(ObjectOptimisticLockingFailureException e) {
        ErrorCode errorCode = ErrorCode.OPTIMISTIC_LOCK_CONFLICT;
        log.warn("Optimistic Lock Conflict: {}", e.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getMessage()));
    }

    // 매핑된 컨트롤러가 없는 요청(예: 아직 구현되지 않은 API 경로 호출)은
    // Spring MVC가 정적 리소스 처리기를 거치며 NoResourceFoundException을 던진다.
    // 이걸 아래 handleException(Exception)이 그대로 잡으면 500으로 응답해버리므로
    // 여기서 먼저 404로 처리한다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        ErrorCode errorCode = ErrorCode.RESOURCE_NOT_FOUND;
        log.warn("No Resource Found: {}", e.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled Global Exception: ", e);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
