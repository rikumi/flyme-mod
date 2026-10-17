"""Exercise production padding/height hooks with Flyme public-template host mocks."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/dev/rikumi/flymemod/XposedInit.java").read_text()


def body_after(marker):
    start = source.index("{", source.index(marker))
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start + 1:end - 1]


methods = "\n".join(
    signature + " {" + body_after(signature) + "}"
    for signature in [
        "private void applyNotificationContentPadding(View content, int requestedPadding)",
        "private int notificationAddedVerticalPadding(View content)",
        "private int notificationPublicHeight(View notification, int nativeHeight) throws ReflectiveOperationException",
    ]
)
measure = body_after('install(loader, row + "NotificationContentView", "onMeasure", chain ->')
layout = body_after('install(loader, row + "ExpandableNotificationRow", "onLayout", chain ->')
java = r'''
import java.util.*;
public class PublicPaddingTest {
    public static class View {
        int[] padding = {3, 5, 7, 9};
        int height = 80, measuredHeight = 100;
        android.view.ViewGroup.LayoutParams params = new android.view.ViewGroup.MarginLayoutParams();
        int getMeasuredWidth() { return 300; }
        int getMeasuredHeight() { return measuredHeight; }
        void measure(int width, int height) { measuredHeight = MeasureSpec.getSize(height); }
        protected void setMeasuredDimension(int width, int height) { measuredHeight = height; }
        static class MeasureSpec {
            static final int EXACTLY = 0x40000000, UNSPECIFIED = 0, AT_MOST = 0x80000000;
            static int makeMeasureSpec(int size, int mode) { return size | mode; }
            static int getSize(int spec) { return spec & 0x3fffffff; }
            static int getMode(int spec) { return spec & 0xc0000000; }
        }
        int getPaddingStart() { return padding[0]; }
        int getPaddingTop() { return padding[1]; }
        int getPaddingEnd() { return padding[2]; }
        int getPaddingBottom() { return padding[3]; }
        void setPaddingRelative(int a, int b, int c, int d) { padding = new int[]{a,b,c,d}; }
        Object getContext() { return null; }
        android.view.ViewGroup.LayoutParams getLayoutParams() { return params; }
    }
    public static class Wrapper {
        View base = new View(), live;
        public View getBasePublicView() { return base; }
        public View getLivePublicView() { return live; }
    }
    public static class Content extends View {
        public int mNotificationMaxHeight = 300, mHeadsUpHeight = 120, mMinContractedHeight = 100;
        public View mExpandedChild = new View(), mHeadsUpChild = new View(), mContractedChild = new View();
        boolean simulate, fixed;
        int naturalContracted = 70, naturalExpanded = 70;
        public Row mContainingNotification;
        Wrapper wrapper = new Wrapper();
        public Wrapper getNotificationViewWrapper() { return wrapper; }
    }
    public static class Row extends View {
        public boolean mShowingPublic = true;
        boolean live;
        Content content = new Content();
        int notifications;
        Row() { content.mContainingNotification = this; }
        public Content getShowingLayout() { return content; }
        public boolean isLiveNotification() { return live; }
        public void notifyHeightChanged(boolean animate) { notifications++; if(animate) throw new AssertionError(); }
    }
    boolean notificationCustomCornersEnabled = true;
    static class Appearance { int padding = 12; float radius(View v) { return padding * 4f; } }
    Appearance activeNotificationAppearanceHooks = new Appearance();
    Map<View, int[]> notificationContentInsets = new WeakHashMap<>();
    Set<View> notificationPaddingHeightChanges = Collections.newSetFromMap(new WeakHashMap<>());
    void loadSettings(Object context) {}
    class Chain {
        View owner;
        boolean fail;
        Chain(View owner) { this.owner = owner; }
        Object getThisObject() { return owner; }
        Object getArg(int index) { return View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST); }
        Object proceed() {
            if (owner instanceof Content c) {
                int extra = notificationAddedVerticalPadding(c);
                if (c.fixed) {
                    int padding = c.getPaddingTop() + c.getPaddingBottom();
                    int maximum = 0;
                    for (View child : new View[]{c.mExpandedChild, c.mContractedChild, c.mHeadsUpChild}) {
                        if (child == null) continue;
                        // Android getChildMeasureSpec: a nonnegative child dimension
                        // is EXACTLY that dimension, even when the parent cap is smaller.
                        child.measuredHeight = child.params.height;
                        maximum = Math.max(maximum, child.measuredHeight);
                    }
                    c.measuredHeight = maximum + padding;
                    return null;
                }
                if (c.simulate) {
                    int padding = c.getPaddingTop() + c.getPaddingBottom();
                    // Flyme NotificationContentView.onMeasure: first contracted spec
                    // includes padding, minimum fallback and expanded fallback omit it.
                    c.mContractedChild.measuredHeight = c.naturalContracted;
                    if (c.naturalContracted < c.mMinContractedHeight)
                        c.mContractedChild.measuredHeight = c.mMinContractedHeight - padding - 2 * ((android.view.ViewGroup.MarginLayoutParams)c.mContractedChild.params).topMargin;
                    int margin = 2 * ((android.view.ViewGroup.MarginLayoutParams)c.mContractedChild.params).topMargin;
                    int maximum = c.mContractedChild.measuredHeight + margin;
                    if (c.mExpandedChild != null) {
                        c.mExpandedChild.measuredHeight = c.naturalExpanded;
                        maximum = Math.max(maximum, c.naturalExpanded + margin);
                        if (c.mExpandedChild.measuredHeight < c.mContractedChild.measuredHeight)
                            c.mExpandedChild.measuredHeight = c.mContractedChild.measuredHeight - padding - margin;
                    }
                    c.measuredHeight = maximum + padding;
                    return null;
                }
                // Flyme measureChildWithMargins subtracts the outer padding.
                check(c.mNotificationMaxHeight - extra == 300);
                check(c.mHeadsUpHeight - extra == 120);
                check(c.mExpandedChild.params.height == 100);
                check(c.mHeadsUpChild.params.height == 100);
            }
            if (fail) throw new IllegalStateException("native failure");
            return null;
        }
    }
    METHODS
    Object measure(Chain chain) throws Throwable { MEASURE }
    Object layout(Chain chain) throws Throwable { LAYOUT }
    static int checks;
    static void check(boolean value) { checks++; if(!value) throw new AssertionError("check " + checks); }
    void run() throws Throwable {
        Row row = new Row(); Content c = row.content;
        measure(new Chain(c));
        check(Arrays.equals(c.padding, new int[]{15,17,19,21}));
        check(notificationPublicHeight(row,80) == 104);
        check(c.mNotificationMaxHeight == 300 && c.mHeadsUpHeight == 120);
        check(c.mExpandedChild.params.height == 100 && c.mHeadsUpChild.params.height == 100);
        layout(new Chain(row)); check(row.notifications == 1);
        for(int i=0; i<20; i++) { measure(new Chain(c)); layout(new Chain(row)); }
        check(row.notifications == 1); check(notificationPublicHeight(row,80) == 104);
        // A re-bound public template changes height without changing the insets.
        c.wrapper.base = new View(); c.wrapper.base.height = 96;
        check(notificationPublicHeight(row,96) == 120);
        // Unlock/expand/private heads-up paths already use getViewHeight().
        row.mShowingPublic = false; check(notificationPublicHeight(row,104) == 104);
        check(notificationPublicHeight(row,224) == 224);
        row.mShowingPublic = true; row.live = true; c.wrapper.live = new View();
        check(notificationPublicHeight(row,64) == 88);
        c.wrapper.live = null; check(notificationPublicHeight(row,96) == 120);
        c.wrapper.base = null; check(notificationPublicHeight(row,104) == 104);
        c.wrapper = null; check(notificationPublicHeight(row,104) == 104);
        c.wrapper = new Wrapper(); row.live = false;
        activeNotificationAppearanceHooks.padding = 18;
        measure(new Chain(c)); layout(new Chain(row));
        check(notificationPublicHeight(row,80) == 116); check(row.notifications == 2);
        check(Arrays.equals(c.padding,new int[]{21,23,25,27}));
        // RemoteViews reapplication overwrites one native edge: preserve it.
        c.padding[0] = 2; measure(new Chain(c));
        check(c.getPaddingStart() == 20 && c.getPaddingTop() == 23);
        Chain failing = new Chain(c); failing.fail = true;
        try { measure(failing); throw new AssertionError(); } catch(IllegalStateException expected) {}
        check(c.mNotificationMaxHeight == 300 && c.mHeadsUpHeight == 120);
        check(c.mExpandedChild.params.height == 100 && c.mHeadsUpChild.params.height == 100);
        notificationCustomCornersEnabled = false;
        measure(new Chain(c)); layout(new Chain(row));
        check(Arrays.equals(c.padding,new int[]{2,5,7,9}));
        check(notificationPublicHeight(row,80) == 80); check(row.notifications == 3);
        measure(new Chain(c)); layout(new Chain(row)); check(row.notifications == 3);
        check(notificationContentInsets.isEmpty() && notificationPaddingHeightChanges.isEmpty());
        // Reuse after disabling/re-enabling starts from the current native baseline.
        notificationCustomCornersEnabled = true;
        measure(new Chain(c)); layout(new Chain(row));
        check(notificationPublicHeight(row,80) == 116); check(row.notifications == 4);
        check(Arrays.equals(c.padding,new int[]{20,23,25,27}));
        // Private short templates: compare against native measurements without
        // module insets, including a contracted-only card (the issue #4 screenshot).
        for (int nativePadding : new int[]{0, 14}) {
            for (int margin : new int[]{0, 4}) for (int natural : new int[]{60, 99, 100, 110, 180}) {
                for (int inset : new int[]{0, 5, 12, 24, 40}) {
                    for (boolean expanded : new boolean[]{false, true}) {
                        Content template = new Row().content;
                        template.padding = new int[]{0,nativePadding/2,0,nativePadding/2};
                        template.simulate = true; template.naturalContracted = natural;
                        template.mHeadsUpChild = null;
                        if (!expanded) template.mExpandedChild = null;
                        for (View child : new View[]{template.mContractedChild, template.mExpandedChild}) if (child != null) {
                            var params = (android.view.ViewGroup.MarginLayoutParams) child.params;
                            params.topMargin = params.bottomMargin = margin;
                            params.height = -2;
                        }
                        int nativeChild = natural < 100 ? 100 - nativePadding - 2*margin : natural;
                        activeNotificationAppearanceHooks.padding = inset;
                        measure(new Chain(template));
                        check(template.mContractedChild.measuredHeight == nativeChild);
                        check(template.measuredHeight == Math.max(nativeChild, expanded ? 70 : 0) + nativePadding + 2*margin + 2*inset);
                        if (expanded && inset > 0) check(template.mExpandedChild.measuredHeight >= nativeChild - nativePadding - 2*margin);
                        // Stable repeated measures never accumulate extra height.
                        int stable = template.measuredHeight;
                        measure(new Chain(template)); check(template.measuredHeight == stable);
                        notificationCustomCornersEnabled = false;
                        measure(new Chain(template));
                        check(template.mContractedChild.measuredHeight == nativeChild);
                        check(template.measuredHeight == Math.max(nativeChild, expanded ? 70 : 0) + nativePadding + 2*margin);
                        notificationCustomCornersEnabled = true;
                    }
                }
            }
        }
        // Fixed RemoteViews must grow ONLY by the container insets, including
        // sizes below the native minimum and short expanded/headsup templates.
        for (int fixed : new int[]{40, 80, 100, 180}) {
            for (int inset : new int[]{0, 5, 12, 24, 40}) {
                for (int variant = 0; variant < 4; variant++) {
                    Content template = new Row().content;
                    template.fixed = true;
                    template.padding = new int[]{0, 0, 0, 0};
                    template.mContractedChild.params.height = fixed;
                    template.mExpandedChild.params.height = fixed / 2;
                    template.mHeadsUpChild.params.height = fixed + 20;
                    if (variant == 0) { template.mExpandedChild = null; template.mHeadsUpChild = null; }
                    if (variant == 1) template.mHeadsUpChild = null;
                    if (variant == 2) template.mExpandedChild = null;
                    int nativeHeight = variant >= 2 ? fixed + 20 : fixed;
                    activeNotificationAppearanceHooks.padding = inset;
                    for (int repeat = 0; repeat < 3; repeat++) {
                        measure(new Chain(template));
                        check(template.measuredHeight == nativeHeight + 2 * inset);
                        check(template.mContractedChild.measuredHeight == fixed);
                        if (template.mExpandedChild != null) check(template.mExpandedChild.measuredHeight == fixed / 2);
                        if (template.mHeadsUpChild != null) check(template.mHeadsUpChild.measuredHeight == fixed + 20);
                    }
                    notificationCustomCornersEnabled = false;
                    measure(new Chain(template));
                    check(template.measuredHeight == nativeHeight);
                    notificationCustomCornersEnabled = true;
                }
            }
        }
        System.out.println("PASS: " + checks + " public padding/measurement/lifecycle checks");
    }
    public static void main(String[] args) throws Throwable { new PublicPaddingTest().run(); }
}
'''.replace("METHODS", methods).replace("MEASURE", measure).replace("LAYOUT", layout)

with tempfile.TemporaryDirectory(prefix="flyme-public-padding-", dir="/private/tmp") as directory:
    path = Path(directory)
    (path / "PublicPaddingTest.java").write_text(java)
    stub = path / "android/view/ViewGroup.java"
    stub.parent.mkdir(parents=True)
    stub.write_text("package android.view; public class ViewGroup { public static class LayoutParams { public int height=100; } public static class MarginLayoutParams extends LayoutParams { public int topMargin, bottomMargin; } }")
    jdk = Path("/opt/homebrew/opt/openjdk@21/bin")
    subprocess.run([str(jdk / "javac"), "-d", directory, str(stub), str(path / "PublicPaddingTest.java")], check=True)
    subprocess.run([str(jdk / "java"), "-cp", directory, "PublicPaddingTest"], check=True)
