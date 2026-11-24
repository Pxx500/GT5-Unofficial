package gregtech.api.metatileentity.implementations.integratedfluid;

import gregtech.api.metatileentity.implementations.integratedfluid.state.IFNNetworkStatus;

public final class IFNNetworkTransferGate {

    private IFNNetworkTransferGate() {}

    public static boolean isOperational(IntegratedFluidNetwork network) {
        return network != null && network.getNetworkStatus() == IFNNetworkStatus.NORMAL;
    }

    public static boolean canInjectFromGtPipe(IntegratedFluidNetwork network) {
        return isOperational(network) && network.getTopologySnapshot().chamberVolume().isPresent()
            && network.getPressure() <= IFNPressurePolicy.injectorCutoffPressureBar();
    }

    public static boolean canExtractToGtPipe(IntegratedFluidNetwork network) {
        return isOperational(network);
    }

    public static boolean canReceiveFromMachine(IntegratedFluidNetwork network) {
        return isOperational(network);
    }

    public static boolean canProvideToMachine(IntegratedFluidNetwork network) {
        return isOperational(network);
    }
}
