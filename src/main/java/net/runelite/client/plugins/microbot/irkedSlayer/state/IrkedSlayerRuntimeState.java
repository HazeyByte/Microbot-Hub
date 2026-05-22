package net.runelite.client.plugins.microbot.irkedSlayer.state;

import net.runelite.client.plugins.microbot.irkedSlayer.IrkedSlayerState;

public class IrkedSlayerRuntimeState {
    private volatile String currentTask = "";
    private volatile int taskRemaining = 0;
    private volatile boolean hasTask = false;
    private volatile IrkedSlayerState state = IrkedSlayerState.IDLE;
    private volatile String currentLocation = "";
    private volatile int slayerPoints = 0;

    public String getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(String currentTask) {
        this.currentTask = currentTask != null ? currentTask : "";
    }

    public int getTaskRemaining() {
        return taskRemaining;
    }

    public void setTaskRemaining(int taskRemaining) {
        this.taskRemaining = taskRemaining;
    }

    public boolean hasTask() {
        return hasTask;
    }

    public void setHasTask(boolean hasTask) {
        this.hasTask = hasTask;
    }

    public IrkedSlayerState getState() {
        return state;
    }

    public void setState(IrkedSlayerState state) {
        this.state = state == null ? IrkedSlayerState.ERROR : state;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(String currentLocation) {
        this.currentLocation = currentLocation == null ? "" : currentLocation;
    }

    public int getSlayerPoints() {
        return slayerPoints;
    }

    public void setSlayerPoints(int slayerPoints) {
        this.slayerPoints = slayerPoints;
    }

    public void reset() {
        hasTask = false;
        currentTask = "";
        taskRemaining = 0;
        state = IrkedSlayerState.IDLE;
        currentLocation = "";
        slayerPoints = 0;
    }
}
