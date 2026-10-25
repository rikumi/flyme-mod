package dev.rikumi.flymemod;

final class EarphoneNoiseState {
    private String device;
    private int reported, requested;

    synchronized int observed(String id, int value) {
        if (!id.equals(device)) { clear(); device=id; }
        if (value==requested) requested=0;
        reported=value;
        return requested!=0?requested:reported;
    }

    synchronized int target(String id, int fallback) {
        int current=id.equals(device)&&requested!=0?requested:fallback;
        return current==3?2:3;
    }

    synchronized void requested(String id, int value) {
        if (!id.equals(device)) { clear(); device=id; }
        requested=value;
    }

    synchronized void clear() { device=null; reported=0; requested=0; }
}
