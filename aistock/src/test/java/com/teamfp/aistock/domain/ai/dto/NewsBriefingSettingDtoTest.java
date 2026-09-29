package com.teamfp.aistock.domain.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.ai.dto.request.NewsBriefingSettingRequest;
import com.teamfp.aistock.domain.ai.dto.response.NewsBriefingSettingResponse;

import tools.jackson.databind.json.JsonMapper;

class NewsBriefingSettingDtoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void acceptsFrontendAndPreviousTimeFields() throws Exception {
        String frontend = "{\"outletDomain\":\"hankyung.com\",\"deliveryTime\":\"09:30\"}";
        String previous = "{\"outletDomain\":\"hankyung.com\",\"briefingTime\":\"09:30\"}";

        assertThat(mapper.readValue(frontend, NewsBriefingSettingRequest.class).deliveryTime())
                .isEqualTo(LocalTime.of(9, 30));
        assertThat(mapper.readValue(previous, NewsBriefingSettingRequest.class).deliveryTime())
                .isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void returnsBothTimeFieldsForExistingClients() throws Exception {
        var response = new NewsBriefingSettingResponse("hankyung.com", "한국경제", LocalTime.of(9, 30), null);

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"deliveryTime\":\"09:30:00\"");
        assertThat(json).contains("\"briefingTime\":\"09:30:00\"");
    }
}
