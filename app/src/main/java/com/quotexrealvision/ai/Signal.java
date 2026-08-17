package com.quotexrealvision.ai;

import java.util.List;

public final class Signal {
    public final String label;
    public final double upProbability;
    public final double confidence;
    public final double agreement;
    public final List<String> reasons;

    public Signal(String label,
                  double upProbability,
                  double confidence,
                  double agreement,
                  List<String> reasons) {
        this.label = label;
        this.upProbability = upProbability;
        this.confidence = confidence;
        this.agreement = agreement;
        this.reasons = reasons;
    }
}
