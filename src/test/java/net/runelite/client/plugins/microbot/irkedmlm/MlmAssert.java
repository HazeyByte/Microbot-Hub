package net.runelite.client.plugins.microbot.irkedmlm;

/**
 * Minimal JUnit-free assertions so the irkedMLM checks run as plain {@code main()} self-checks — the
 * repo has no JUnit dependency and its convention is plain-main checks (see IrkedGoatkillerGeometryCheck).
 * Signatures mirror the JUnit 4/5 methods these tests were written against (message-last), so the test
 * bodies are unchanged apart from the import.
 */
public final class MlmAssert {
    private MlmAssert() {}

    public static void assertTrue(boolean cond) {
        if (!cond) throw new AssertionError("expected true");
    }

    public static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void assertFalse(boolean cond) {
        if (cond) throw new AssertionError("expected false");
    }

    public static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError(msg);
    }

    public static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + " but was " + actual);
    }

    public static void assertEquals(double expected, double actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + " but was " + actual);
    }

    public static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
