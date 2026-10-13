package dev.rikumi.flymemod;

import java.util.Arrays;

/** An observed RGB value wins only when its 1% neighborhood covers most of the line. */
final class NavigationColorSampler {
    private static final int CHANNEL_TOLERANCE = 3; // round(255 * 1%)

    static Integer dominantColor(int[] pixels) {
        int[] colors = new int[pixels.length];
        int size = 0;
        for (int pixel : pixels) {
            // An empty/transparent part of a task capture is not an app color.
            if ((pixel >>> 24) == 255) colors[size++] = pixel & 0xffffff;
        }
        if (size <= pixels.length / 2) return null;
        Arrays.sort(colors, 0, size);
        int[] counts = new int[size];
        int unique = 0, exactWinner = 0;
        for (int i = 0; i < size;) {
            int value = colors[i], end = i + 1;
            while (end < size && colors[end] == value) end++;
            colors[unique] = value; counts[unique] = end - i;
            if (counts[unique] > counts[exactWinner]) exactWinner = unique;
            unique++; i = end;
        }
        if (counts[exactWinner] > pixels.length / 2) return 0xff000000 | colors[exactWinner];

        int winner = 0, votes = 0, start = 0, end = 0;
        for (int i = 0; i < unique; i++) {
            int red = colors[i] >>> 16, green = (colors[i] >>> 8) & 255, blue = colors[i] & 255;
            while (start < unique && (colors[start] >>> 16) < red - CHANNEL_TOLERANCE) start++;
            while (end < unique && (colors[end] >>> 16) <= red + CHANNEL_TOLERANCE) end++;
            int count = 0;
            for (int j = start; j < end; j++) {
                if (Math.abs(((colors[j] >>> 8) & 255) - green) <= CHANNEL_TOLERANCE
                        && Math.abs((colors[j] & 255) - blue) <= CHANNEL_TOLERANCE) count += counts[j];
            }
            // Count neighbors around an actual color, not a transitive chain or
            // an average. Ties favor the most frequent exact value in the group.
            if (count > votes || (count == votes && counts[i] > counts[winner])) {
                winner = i; votes = count;
            }
        }
        return votes > pixels.length / 2 ? 0xff000000 | colors[winner] : null;
    }
}
