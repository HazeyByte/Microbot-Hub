package net.runelite.client.plugins.microbot.irkedSlayer.state;

import net.runelite.client.plugins.microbot.irkedSlayer.IrkedSlayerState;

import javax.inject.Singleton;

@Singleton
public class IrkedSlayerStateController {
    private final IrkedSlayerRuntimeState runtime = new IrkedSlayerRuntimeState();

    public IrkedSlayerState getState() {
        return runtime.getState();
    }

    public void setState(IrkedSlayerState state) {
        runtime.setState(state);
    }

    public void updateTaskInfo(boolean hasSlayerTask, String taskName, int remaining) {
        runtime.setHasTask(hasSlayerTask);
        runtime.setCurrentTask(taskName);
        runtime.setTaskRemaining(remaining);
    }

    public String getCurrentTask() {
        return runtime.getCurrentTask();
    }

    public int getTaskRemaining() {
        return runtime.getTaskRemaining();
    }

    public boolean hasTask() {
        return runtime.hasTask();
    }

    public String getCurrentLocation() {
        return runtime.getCurrentLocation();
    }

    public void setCurrentLocation(String currentLocation) {
        runtime.setCurrentLocation(currentLocation);
    }

    public int getSlayerPoints() {
        return runtime.getSlayerPoints();
    }

    public void setSlayerPoints(int slayerPoints) {
        runtime.setSlayerPoints(slayerPoints);
    }

    public void reset() {
        runtime.reset();
    }

    public IrkedSlayerRuntimeState getRuntimeState() {
        return runtime;
    }
}
