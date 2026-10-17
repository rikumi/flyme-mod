package dev.rikumi.flymemod;
import java.util.List;
public final class HeyMelodyNoiseModesTest {
    static int checks;
    static HeyMelodyNoiseModes.Entry entry(int index, int type, HeyMelodyNoiseModes.Entry... children) {
        return new HeyMelodyNoiseModes.Entry(index, type, true, List.of(children));
    }
    static void equal(int actual, int expected) {
        checks++; if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }
    static HeyMelodyNoiseModes modes(HeyMelodyNoiseModes.Entry... entries) { return new HeyMelodyNoiseModes(List.of(entries)); }
    public static void main(String[] args) {
        HeyMelodyNoiseModes modern = modes(entry(0,1,entry(3,1)), entry(1,5,entry(4,4),entry(5,8)),
                entry(2,2,entry(8,2)), entry(11,10,entry(12,11)));
        equal(modern.target(2),1); equal(modern.target(3),2);
        equal(modern.mode(0),1); equal(modern.mode(3),1);
        equal(modern.mode(1),2); equal(modern.mode(4),2); equal(modern.mode(5),2);
        equal(modern.mode(11),2); equal(modern.mode(12),2); equal(modern.mode(8),3);
        equal(modern.mode(-1),0); equal(modern.mode(32),0); equal(modern.mode(10),0);
        equal(modern.target(0),-1); equal(modern.target(1),-1);
        HeyMelodyNoiseModes old = modes(entry(0,1),entry(1,2),entry(2,3),entry(3,4));
        equal(old.target(2),3); equal(old.target(3),1); equal(old.mode(2),2);
        equal(modes(entry(0,1),entry(1,2)).target(3),-1);
        equal(modes(entry(0,1),entry(1,5)).mode(1),0);
        equal(modes(entry(-1,1),entry(1,5),entry(2,2)).mode(1),0);
        equal(modes(entry(32,1),entry(1,5),entry(2,2)).target(2),-1);
        equal(modes(entry(1,5),entry(2,2),entry(1,1)).mode(1),0);
        HeyMelodyNoiseModes conditional = modes(new HeyMelodyNoiseModes.Entry(1,5,false,List.of()),entry(4,4),entry(2,2));
        equal(conditional.target(2),4);
        equal(modes(entry(1,5),new HeyMelodyNoiseModes.Entry(2,2,false,List.of())).mode(1),0);
        equal(modes(entry(1,10),entry(2,2)).target(2),1);
        equal(modes(entry(1,99),entry(2,2)).target(2),-1);
        System.out.println("PASS: " + checks + " HeyMelody mode checks");
    }
}
