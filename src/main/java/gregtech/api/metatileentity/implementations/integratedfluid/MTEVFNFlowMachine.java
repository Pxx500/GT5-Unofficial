package gregtech.api.metatileentity.implementations.integratedfluid;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.*;
import static gregtech.api.enums.HatchElement.Maintenance;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.GTStructureUtility.ofHatchAdder;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.BlockCasings2;

/** Steel machine housing with separate VFN inlet and outlet hatches. */
abstract class MTEVFNFlowMachine<T extends MTEVFNFlowMachine<T>> extends MTEEnhancedMultiBlockBase<T>
    implements ISurvivalConstructable {

    protected MTEIntegratedFluidInputHatch input;
    protected MTEIntegratedFluidOutputHatch output;

    protected MTEVFNFlowMachine(int id, String name, String regionalName) { super(id, name, regionalName); }
    protected MTEVFNFlowMachine(String name) { super(name); }

    protected static <M extends MTEVFNFlowMachine<M>> IStructureDefinition<M> steelHousing(Class<M> type, HatchElement power) {
        int casing = ((BlockCasings2) GregTechAPI.sBlockCasings2).getTextureIndex(0);
        return StructureDefinition.<M>builder()
            .addShape("main", transpose(new String[][] { { "CCC", "CCC", "CCC" }, { "C~C", "CCC", "CCC" }, { "CCC", "CCC", "CCC" } }))
            .addElement('C', ofChain(ofHatchAdder(MTEVFNFlowMachine::addPort, casing, 1),
                buildHatchAdder(type).atLeast(power, Maintenance).casingIndex(casing)
                    .hint(1).buildAndChain(ofBlock(GregTechAPI.sBlockCasings2, 0)))).build();
    }

    private boolean addPort(IGregTechTileEntity tile, Short texture) {
        if (tile == null) return false;
        if (input == null && tile.getMetaTileEntity() instanceof MTEIntegratedFluidInputHatch hatch) {
            input = hatch;
            hatch.updateTexture(texture);
            return true;
        }
        if (output == null && tile.getMetaTileEntity() instanceof MTEIntegratedFluidOutputHatch hatch) {
            output = hatch;
            hatch.updateTexture(texture);
            return true;
        }
        return false;
    }

    protected abstract boolean hasPowerHatch();

    public final float inletBar() { return input == null || !input.isValid() || input.getNetwork() == null ? 0 : input.getNetwork().getPressure(); }
    public final float outletBar() { return output == null || !output.isValid() || output.getNetwork() == null ? 0 : output.getNetwork().getPressure(); }

    @Override public final boolean checkMachine(IGregTechTileEntity tile, ItemStack stack) {
        input = null;
        output = null;
        boolean formed = checkPiece("main", 1, 1, 0) && input != null && output != null
            && hasPowerHatch() && mMaintenanceHatches.size() == 1;
        if (formed) doPeriodicChecks = true;
        return formed;
    }

    @Override public final void construct(ItemStack stack, boolean hintsOnly) { buildPiece("main", stack, hintsOnly, 1, 1, 0); }
    @Override public final int survivalConstruct(ItemStack stack, int budget, ISurvivalBuildEnvironment env) {
        return mMachine ? -1 : survivialBuildPiece("main", stack, 1, 1, 0, budget, env, false, true);
    }
}
