package playground.ci

/** A version being released. */
class Release implements Serializable {
    static final int ATTEMPTS = 3
    private static final String PREFIX = 'v'

    final String version

    Release(String version) {
        this.version = version
    }

    String tag() {
        return PREFIX + version
    }

    boolean isStable() {
        def parts = version.tokenize('.-')
        return parts.every { it ==~ /\d+/ } && !snapshot()
    }

    private boolean snapshot() {
        version.endsWith('SNAPSHOT')
    }
}
