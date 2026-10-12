package dev.rikumi.flymemod;

import java.util.*;

/** Host checks for the actual grid geometry and persisted order helper. */
public final class HiddenAppOrderTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        Set<String> membership = new HashSet<>(List.of("a", "b", "c"));
        equal(List.of("a", "b", "c"), HiddenAppOrder.reconcile(membership, null)); // upgrade
        equal(List.of("c", "a", "b"), HiddenAppOrder.reconcile(membership, "c\na\nc\nmissing"));
        membership.add("aa"); // new package sorts before b, but must append
        equal(List.of("c", "a", "b", "aa"), HiddenAppOrder.reconcile(membership, "c\na\nb"));
        membership.remove("a");
        String deleted = String.join("\n", HiddenAppOrder.reconcile(membership, "c\na\nb\naa"));
        membership.add("a");
        equal(List.of("c", "b", "aa", "a"), HiddenAppOrder.reconcile(membership, deleted));

        List<Integer> baseline = new ArrayList<>();
        for (int i = 0; i < 37; i++) baseline.add(i);
        List<Integer> preview = HiddenAppOrder.move(baseline, 11, 12);
        equal(12, preview.get(11)); equal(11, preview.get(12)); // 12th -> 13th
        preview = HiddenAppOrder.move(preview, 12, 36);
        equal(11, preview.get(36));
        equal(11, baseline.get(11)); equal(36, baseline.get(36)); // cancel uses unchanged baseline
        equal(baseline, HiddenAppOrder.move(preview, 36, 11));
        equal(preview, HiddenAppOrder.move(preview, -1, 0));
        equal(preview, HiddenAppOrder.move(preview, 0, 37));
        equal(37, new HashSet<>(preview).size());
        // Every source/target insertion preserves the set and can be reversed.
        for (int from = 0; from < 37; from++) for (int to = 0; to < 37; to++) {
            List<Integer> result = HiddenAppOrder.move(baseline, from, to);
            equal(from, result.get(to));
            equal(baseline, HiddenAppOrder.move(result, to, from));
        }
        for (int page = 0; page < 4; page++) for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 3; col++) {
                float x = col * 104 + 52, y = row * 108 + 54;
                int index = page * 12 + row * 3 + col;
                equal(Math.min(36, index), HiddenAppOrder.slot(x, y, 312, 432, page, 37));
                equal(index < 37 ? index : -1, HiddenAppOrder.hit(x, y, 312, 432, page, 37));
            }
        }
        equal(12, HiddenAppOrder.slot(300, 430, 312, 432, 1, 13)); // final-page empty cell -> tail
        equal(-1, HiddenAppOrder.hit(300, 430, 312, 432, 1, 13)); // no phantom app/menu
        equal(-1, HiddenAppOrder.hit(-1, 50, 312, 432, 0, 13));
        equal(-1, HiddenAppOrder.hit(312, 50, 312, 432, 0, 13));
        equal(-1, HiddenAppOrder.slot(50, -1, 312, 432, 0, 13));
        equal(-1, HiddenAppOrder.slot(50, 432, 312, 432, 0, 13));
        equal(-1, HiddenAppOrder.slot(50, 50, 0, 432, 0, 13));
        equal(-1, HiddenAppOrder.slot(50, 50, 312, 432, 0, 0));
        equal(0, HiddenAppOrder.edge(0, 50, 312, 432, 0, 4));
        equal(-1, HiddenAppOrder.edge(0, 50, 312, 432, 1, 4));
        equal(1, HiddenAppOrder.edge(312, 50, 312, 432, 0, 4));
        equal(1, HiddenAppOrder.edge(312, 50, 312, 432, 2, 4)); // continuous turn
        equal(0, HiddenAppOrder.edge(312, 50, 312, 432, 3, 4));
        equal(0, HiddenAppOrder.edge(156, 50, 312, 432, 1, 4));
        equal(0, HiddenAppOrder.edge(312, -1, 312, 432, 1, 4));
        equal(0, HiddenAppOrder.edge(312, 432, 312, 432, 1, 4));
        equal(0, HiddenAppOrder.edge(0, 50, 312, 432, 0, 1));
        // Persist / reopen, then reconcile an external add/remove against the drag result.
        List<String> reordered = List.of("aa", "c", "b", "a");
        String persisted = String.join("\n", reordered);
        equal(reordered, HiddenAppOrder.reconcile(membership, persisted));
        membership.remove("b"); membership.add("new");
        equal(List.of("aa", "c", "a", "new"), HiddenAppOrder.reconcile(membership, persisted));
        System.out.println("PASS: " + checks + " hidden-app order/geometry checks");
    }
}
