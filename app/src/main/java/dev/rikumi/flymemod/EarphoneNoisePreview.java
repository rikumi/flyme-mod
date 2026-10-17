package dev.rikumi.flymemod;

/** Last confirmed display state; visibility and service binding do not invalidate it. */
final class EarphoneNoisePreview {
    record State(String address, String deviceId, int mode) {}
    private State state;
    synchronized void observed(String address, String deviceId, int mode) {
        if (address != null && !address.isEmpty() && mode >= 1 && mode <= 3)
            state = new State(address, deviceId, mode);
    }
    synchronized State forOutput(String address, boolean melodyEnabled) {
        if (state == null || !state.address().equalsIgnoreCase(address)) return null;
        return !melodyEnabled && state.deviceId().startsWith("melody:") ? null : state;
    }
    synchronized void disconnected(String address) {
        if (state != null && state.address().equalsIgnoreCase(address)) state = null;
    }
    synchronized void disableMelody() {
        if (state != null && state.deviceId().startsWith("melody:")) state = null;
    }
    synchronized void clear() { state = null; }
}
