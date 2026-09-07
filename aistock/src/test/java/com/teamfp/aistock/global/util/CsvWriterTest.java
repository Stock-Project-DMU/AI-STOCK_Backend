package com.teamfp.aistock.global.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드리뷰 반영(2026-09) — CSV 포뮬러 인젝션 방어를 집중 검증한다. 관리자가 회원/거래 CSV를
 * 엑셀로 열 때, 사용자가 입력한 이름/아이디가 `=`/`+`/`-`/`@`로 시작하면 수식으로 실행되는
 * 문제를 막는 게 핵심이라 그 부분을 명시적으로 검증한다.
 */
class CsvWriterTest {

    private String bodyOf(byte[] csv) {
        // UTF-8 BOM(3바이트) 이후만 문자열로 비교한다.
        return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("UTF-8 BOM으로 시작한다")
    void write_startsWithUtf8Bom() {
        byte[] csv = CsvWriter.write(List.of("헤더"), List.of(List.of("값")));

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
    }

    @Test
    @DisplayName("=로 시작하는 값은 앞에 작은따옴표를 붙여 수식으로 해석되지 않게 한다")
    void write_neutralizesFormulaTriggerEquals() {
        byte[] csv = CsvWriter.write(List.of("이름"), List.of(List.of("=HYPERLINK(\"http://evil\",\"click\")")));

        String body = bodyOf(csv);
        assertThat(body).contains("'=HYPERLINK(");
        assertThat(body).doesNotStartWith("=HYPERLINK");
    }

    @Test
    @DisplayName("+, -, @로 시작하는 값도 각각 무력화한다")
    void write_neutralizesOtherFormulaTriggerChars() {
        byte[] csv = CsvWriter.write(
                List.of("a", "b", "c"),
                List.of(List.of("+1+1", "-2+3", "@SUM(A1:A2)")));

        String body = bodyOf(csv);
        assertThat(body).contains("'+1+1");
        assertThat(body).contains("'-2+3");
        assertThat(body).contains("'@SUM(A1:A2)");
    }

    @Test
    @DisplayName("일반 값(수식 트리거 문자로 시작하지 않음)은 그대로 둔다")
    void write_leavesNormalValuesUntouched() {
        byte[] csv = CsvWriter.write(List.of("이름"), List.of(List.of("홍길동")));

        assertThat(bodyOf(csv)).contains("홍길동");
        assertThat(bodyOf(csv)).doesNotContain("'홍길동");
    }

    @Test
    @DisplayName("콤마·따옴표·줄바꿈이 섞인 값은 큰따옴표로 감싸고 내부 따옴표를 이스케이프한다(RFC 4180)")
    void write_escapesRfc4180SpecialChars() {
        byte[] csv = CsvWriter.write(List.of("메모"), List.of(List.of("a,b\"c\nd")));

        assertThat(bodyOf(csv)).contains("\"a,b\"\"c\nd\"");
    }

    @Test
    @DisplayName("수식 트리거 문자로 시작하면서 동시에 콤마도 포함된 값은 둘 다 처리된다")
    void write_handlesFormulaTriggerAndRfc4180TogetherC() {
        byte[] csv = CsvWriter.write(List.of("메모"), List.of(List.of("=A,B")));

        assertThat(bodyOf(csv)).contains("\"'=A,B\"");
    }

    @Test
    @DisplayName("null 값은 빈 문자열로 처리한다")
    void write_nullValueBecomesEmptyString() {
        List<String> row = new java.util.ArrayList<>();
        row.add(null);
        byte[] csv = CsvWriter.write(List.of("col"), List.of(row));

        assertThat(bodyOf(csv)).isEqualTo("col\r\n\r\n");
    }
}
