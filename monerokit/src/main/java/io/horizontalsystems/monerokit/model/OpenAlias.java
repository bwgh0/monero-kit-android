package io.horizontalsystems.monerokit.model;

/** DNSSEC-validating TXT lookup for OpenAlias names. See the OpenAlias send spec. */
public final class OpenAlias {
    static {
        System.loadLibrary("monerujo");
    }

    private OpenAlias() {
    }

    /**
     * Looks up the TXT records of name through the DNS-over-TCP forwarder on the
     * IPv4 loopback at forwarderPort, and validates DNSSEC from the root. Blocks
     * for up to timeoutMs. Returns one JSON object. Call it off the main thread.
     */
    public static native String lookupTxt(String name, int forwarderPort, int timeoutMs);
}
