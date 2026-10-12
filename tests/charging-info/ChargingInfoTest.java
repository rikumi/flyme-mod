package dev.rikumi.flymemod;

import java.util.Locale;

public final class ChargingInfoTest {
    static int checks;
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    static void text(long mv, long ma, int mode, String expected) {
        check(expected.equals(new ChargingInfo.Sample(mv * 1000, ma * 1000).format(mode)));
    }
    public static void main(String[] args) {
        for (Locale locale : new Locale[]{Locale.US, Locale.CHINA, Locale.GERMANY, Locale.forLanguageTag("ar")}) {
            Locale.setDefault(locale);
            text(12000,3000,1,"36W"); text(12001,3000,1,"37W");
            text(5000,3000,2,"5V 3A"); text(5001,2650,2,"6V 2.7A");
            text(4999,2649,2,"5V 2.6A"); text(5000,2950,2,"5V 3A");
            text(5000,49,2,""); text(5000,50,2,"5V 0.1A");
            text(5000,1,1,"1W"); text(30000,15000,1,"450W");
        }
        for (long mv : new long[]{-5000,0,2999,30001,5000000,Long.MAX_VALUE}) text(mv,3000,1,"");
        for (long ma : new long[]{-3000,0,15001,3000000,Long.MAX_VALUE,Long.MIN_VALUE}) text(5000,ma,2,"");
        text(5000,3000,0,""); text(5000,3000,3,""); text(5000,3000,-1,"");
        check(ChargingInfo.parse("1 0 5000 3000 1 0").format(1).equals("15W"));
        check(ChargingInfo.parse("0 1 9000 2700 0 1").format(2).equals("9V 2.7A"));
        for (String raw : new String[]{"", "0 0 4000 0 0 0", "1 1 5000 3000 1 1", "1 0 5000 3000 0 0",
                "0 1 6000 1000 1 0", "1 0 5000 -3000 1 0", "1 0 0 3000 1 0", "1 0 5000000 3000000 1 0",
                "1 0 NaN 3000 1 0", "1 0 5000 3000 1", "1 0 5000 3000 1 0 extra", "Permission denied",
                "1 0 999999999999999999999999 1 1 0"}) check(ChargingInfo.parse(raw) == null);
        check(ChargingInfo.parse(null) == null);
        String chargingSnapshot = "1 0 17463000 1290000 1 0";
        check(ChargingInfo.parse(chargingSnapshot) == null); // Old unit validation rejected this real device sample.
        check(ChargingInfo.parseMicro(chargingSnapshot).format(1).equals("23W"));
        check(ChargingInfo.parseMicro(chargingSnapshot).format(2).equals("18V 1.3A"));
        check(ChargingInfo.parse(ChargingInfo.legacySample(chargingSnapshot)).format(1).equals("23W"));
        check(ChargingInfo.parseMicro("1 0 17045000 198000 1 0").format(2).equals("18V 0.2A"));
        check(ChargingInfo.parseMicro("1 0 12000001 3000000 1 0").format(1).equals("37W"));
        check(ChargingInfo.parseMicro("1 0 5000001 3000000 1 0").format(2).equals("6V 3A"));
        check(ChargingInfo.parseMicro("0 0 4000 0 0 0") == null);
        check(ChargingInfo.parseMicro("1 0 17463000 1290000 0 0") == null);
        check(ChargingInfo.parseMicro("1 0 17463000 -1290000 1 0") == null);
        check(ChargingInfo.parseMicro("1 0 5000 3000 1 0") == null);
        check(ChargingInfo.parse("1 0 9223372036854775807 1 1 0") == null);
        check(ChargingInfo.legacySample("0 0 4000 0 0 0").isEmpty());
        // Actual native state predicate: off, detach, invisible, noncharging and bypass clear independently.
        for(int mode=-1;mode<=3;mode++) for(int bits=0;bits<16;bits++) {
            boolean attached=(bits&1)!=0,visible=(bits&2)!=0,charging=(bits&4)!=0,bypass=(bits&8)!=0;
            check(ChargingInfo.active(mode,attached,visible,charging,bypass)
                    == ((mode==1 || mode==2) && bits==7));
        }
        // Broad formatter rounding checks against independent decimal arithmetic.
        for(long mv=3000;mv<=30000;mv+=137) for(long ma=1;ma<=15000;ma+=191) {
            String watts = new java.math.BigDecimal(mv).multiply(new java.math.BigDecimal(ma))
                    .divide(new java.math.BigDecimal(1000000),0,java.math.RoundingMode.CEILING).toPlainString()+"W";
            text(mv,ma,1,watts);
        }
        System.out.println("PASS: "+checks+" charging formatter/unit/validity/native-state checks");
    }
}
