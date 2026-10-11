package dev.rikumi.flymemod;

import android.animation.AnimatorSet;

/** Base durations only: ValueAnimator applies the user's system duration scale at playback. */
final class StackedRecentsTiming {
    // OverviewState#getTransitionDuration in the reference launcher uses 380ms for gestures.
    private static final long OVERVIEW_ENTRY_MS = 380L;
    private static final double DURATION_FACTOR = 1.10;

    static long entryDuration(AnimatorSet nativeAnimation) {
        // getDuration() can be -1 when each child owns its duration. Preserve
        // the native sequence/delays instead of replacing that case with 340ms.
        long duration = nativeAnimation == null ? OVERVIEW_ENTRY_MS : nativeAnimation.getTotalDuration();
        if (duration < 0L) duration = OVERVIEW_ENTRY_MS;
        return Math.round(duration * DURATION_FACTOR);
    }

    static long settleDuration() { return Math.round(200L * DURATION_FACTOR); }

    private StackedRecentsTiming() {}
}
