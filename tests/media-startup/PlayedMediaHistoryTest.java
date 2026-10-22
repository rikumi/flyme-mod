package dev.rikumi.flymemod;

import java.util.Arrays;
import java.util.List;

public final class PlayedMediaHistoryTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    private static List<?> track(int user, String app, String session, String title) {
        return Arrays.asList(user, app, session, title, "artist");
    }
    public static void main(String[] args) {
        PlayedMediaHistory h = new PlayedMediaHistory();
        List<?> a = track(0,"telegram","session1","A");
        check(!h.allow(a,false,true), "Unplayed boot entry hidden");
        check(h.allow(a,false,false), "Switch off preserves native entry");
        check(!h.allow(a,false,true), "Showing native entry is not playback evidence");
        check(h.allow(a,true,true), "Actual playback accepted");
        check(h.allow(a,false,true), "Paused played track retained");
        check(!h.allow(track(0,"telegram","session1","B"),false,true), "Same session new unplayed track hidden");
        check(!h.allow(track(10,"telegram","session1","A"),false,true), "User isolation");
        check(!h.allow(track(0,"other","session1","A"),false,true), "Package isolation");
        check(!h.allow(track(0,"telegram","session2","A"),false,true), "Session isolation");
        List<?> b = track(0,"music","session2","B");
        check(h.allow(b,true,false), "Playback observed with option off");
        check(h.allow(b,false,true), "Enabling preserves observed paused media");
        check(!new PlayedMediaHistory().allow(a,false,true), "Restart cannot invent playback history");
        for (int i=0;i<300;i++) h.allow(track(0,"music","session", "track"+i),true,true);
        check(!h.allow(a,false,true), "Old entries bounded");
        check(h.allow(track(0,"music","session","track299"),false,true), "Recent played entry retained");
        System.out.println("PASS: "+checks+" media playback history checks");
    }
}
