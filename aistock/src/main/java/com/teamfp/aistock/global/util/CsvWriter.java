package com.teamfp.aistock.global.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 관리자 CSV 내보내기(ADMIN_API_BACKEND_HANDOFF.md 6.2)용 최소 CSV 작성 유틸. 값에 쉼표/큰따옴표/
 * 줄바꿈이 섞여 있으면 큰따옴표로 감싸고 내부 큰따옴표는 두 번 반복해 이스케이프한다(RFC 4180).
 * 엑셀에서 한글이 깨지지 않도록 UTF-8 BOM을 파일 맨 앞에 붙인다.
 *
 * CSV 인젝션(포뮬러 인젝션) 방어(코드리뷰 반영, 2026-09): 회원 이름/아이디처럼 사용자가 직접
 * 입력한 값이 그대로 셀 값이 되는데(AdminUserService.exportUsersCsv 등), 그 값이 `=`, `+`, `-`,
 * `@`로 시작하면 엑셀 등 스프레드시트 프로그램이 이를 수식으로 해석해 실행해버린다(예: 이름을
 * `=HYPERLINK("http://evil","click")`로 가입한 뒤 관리자가 CSV를 열면 그대로 실행됨). 그런
 * 문자로 시작하는 값 앞에 작은따옴표(')를 붙여 "텍스트 그대로"로 강제한다 — 대부분의 스프레드시트
 * 프로그램이 작은따옴표 접두사를 셀에 표시하지 않고 서식 지정자로만 처리한다.
 */
public final class CsvWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String LINE_SEPARATOR = "\r\n";

    private CsvWriter() {
    }

    public static byte[] write(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private static void appendRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        sb.append(LINE_SEPARATOR);
    }

    private static final String FORMULA_TRIGGER_CHARS = "=+-@";

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        // 포뮬러 인젝션 방어 — 이스케이프 판단(콤마/따옴표/줄바꿈 여부)보다 먼저 적용해야
        // 뒤이은 큰따옴표 감싸기 로직이 접두사 붙은 값에도 정상적으로 이어진다.
        if (!value.isEmpty() && FORMULA_TRIGGER_CHARS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
