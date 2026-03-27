package com.legalpartner.model.dto.review;
import java.util.List;
public record DashboardDto(List<MatterReviewDto> needsAction, List<MatterReviewDto> teamActivity,
                           List<MatterReviewDto> recentlyCompleted) {}
