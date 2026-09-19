package com.teamfp.aistock.domain.ai.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "planning_preferences") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanningPreferences {
    @Id private Long userId;
    @Version private Long version;
    @Column(nullable = false, columnDefinition = "TEXT") private String selections;
    public static PlanningPreferences from(Long userId) {
        PlanningPreferences preferences = new PlanningPreferences();
        preferences.userId = userId;
        preferences.selections = "{\"savedBriefingDates\":[],\"linkedBriefingDates\":[],\"linkedGoalPlanIds\":[]}";
        return preferences;
    }
    public void updateSelections(String selections) { this.selections = selections; }
}
