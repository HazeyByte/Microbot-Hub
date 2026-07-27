package net.runelite.client.plugins.microbot.irkedfarmer.task;

/**
 * Outcome of running (or attempting) a single farming task. Every result carries a human-readable
 * message that explains the decision, so the scheduler log reads like a diary of what happened.
 */
public final class TaskResult {
    public enum Status {
        COMPLETED,   // ran to completion
        SKIPPED,     // nothing due (patches still growing) or disabled
        INFEASIBLE,  // plan could not fit 28 slots / required item unavailable
        FAILED       // ran but hit an unrecoverable error
    }

    public final Status status;
    public final String message;

    private TaskResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static TaskResult completed(String message) { return new TaskResult(Status.COMPLETED, message); }
    public static TaskResult skipped(String message)   { return new TaskResult(Status.SKIPPED, message); }
    public static TaskResult infeasible(String message){ return new TaskResult(Status.INFEASIBLE, message); }
    public static TaskResult failed(String message)    { return new TaskResult(Status.FAILED, message); }

    @Override
    public String toString() {
        return status + ": " + message;
    }
}
