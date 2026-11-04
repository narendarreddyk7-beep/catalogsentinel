package io.catalogsentinel.domain.evaluation;

/** Binary confusion matrix with the usual derived metrics. */
public final class ConfusionMatrix {

    private long truePositives;
    private long falsePositives;
    private long falseNegatives;
    private long trueNegatives;

    public void record(boolean predicted, boolean actual) {
        if (predicted && actual) {
            truePositives++;
        } else if (predicted) {
            falsePositives++;
        } else if (actual) {
            falseNegatives++;
        } else {
            trueNegatives++;
        }
    }

    public long truePositives() {
        return truePositives;
    }

    public long falsePositives() {
        return falsePositives;
    }

    public long falseNegatives() {
        return falseNegatives;
    }

    public long trueNegatives() {
        return trueNegatives;
    }

    public double precision() {
        long denominator = truePositives + falsePositives;
        return denominator == 0 ? 1.0 : (double) truePositives / denominator;
    }

    public double recall() {
        long denominator = truePositives + falseNegatives;
        return denominator == 0 ? 1.0 : (double) truePositives / denominator;
    }

    public double f1() {
        double p = precision();
        double r = recall();
        return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "TP=%d FP=%d FN=%d TN=%d P=%.4f R=%.4f F1=%.4f",
                truePositives, falsePositives, falseNegatives, trueNegatives, precision(), recall(), f1());
    }
}
