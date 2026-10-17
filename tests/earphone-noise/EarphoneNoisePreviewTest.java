package dev.rikumi.flymemod;
public final class EarphoneNoisePreviewTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        EarphoneNoisePreview cache=new EarphoneNoisePreview();
        check(cache.forOutput("A",true)==null);
        cache.observed("A","melody:A",2);
        // Reopening and pending reads do not discard the last confirmed state.
        for(int i=0;i<10;i++)check(cache.forOutput("A",true).mode()==2);
        cache.observed("A","melody:A",0);
        check(cache.forOutput("a",true).mode()==2);
        check(cache.forOutput("B",true)==null);
        check(cache.forOutput("A",false)==null);
        cache.observed("A","melody:A",3);
        check(cache.forOutput("A",true).mode()==3);
        cache.disconnected("B");check(cache.forOutput("A",true)!=null);
        cache.disconnected("a");check(cache.forOutput("A",true)==null);
        cache.observed("A","melody:A",2);cache.disableMelody();
        check(cache.forOutput("A",true)==null);
        cache.observed("A","meizu-device",1);cache.disableMelody();
        check(cache.forOutput("A",false).mode()==1);
        cache.clear();check(cache.forOutput("A",true)==null);
        System.out.println("PASS: reopen, pending read, fresh state, device isolation, disconnect and settings invalidation");
    }
}
