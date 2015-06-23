package gregtech.loaders.postload;

import cpw.mods.fml.common.Loader;
import gregtech.api.GregTech_API;
import gregtech.api.enums.ConfigCategories;
import gregtech.api.enums.Materials;
import gregtech.api.util.GT_Config;
import gregtech.common.GT_Worldgen_GT_Ore_Layer;
import gregtech.common.GT_Worldgen_GT_Ore_SmallPieces;
import gregtech.common.GT_Worldgen_Stone;
import gregtech.common.GT_Worldgenerator;

public class GT_Worldgenloader
  implements Runnable
{
  public void run()
  {
    boolean tPFAA = (GregTech_API.sWorldgenFile.get(ConfigCategories.general, "AutoDetectPFAA", true)) && (Loader.isModLoaded("PFAAGeologica"));
    
    new GT_Worldgenerator();
    
    new GT_Worldgen_Stone("overworld.stone.blackgranite.tiny", true, GregTech_API.sBlockGranites, 0, 0, 1, 50, 48, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.blackgranite.small", true, GregTech_API.sBlockGranites, 0, 0, 1, 100, 96, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.blackgranite.medium", true, GregTech_API.sBlockGranites, 0, 0, 1, 200, 144, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.blackgranite.large", true, GregTech_API.sBlockGranites, 0, 0, 1, 300, 192, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.blackgranite.huge", true, GregTech_API.sBlockGranites, 0, 0, 1, 400, 240, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.redgranite.tiny", true, GregTech_API.sBlockGranites, 8, 0, 1, 50, 48, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.redgranite.small", true, GregTech_API.sBlockGranites, 8, 0, 1, 100, 96, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.redgranite.medium", true, GregTech_API.sBlockGranites, 8, 0, 1, 200, 144, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.redgranite.large", true, GregTech_API.sBlockGranites, 8, 0, 1, 300, 192, 0, 120, null, false);
    new GT_Worldgen_Stone("overworld.stone.redgranite.huge", true, GregTech_API.sBlockGranites, 8, 0, 1, 400, 240, 0, 120, null, false);
    
    new GT_Worldgen_Stone("nether.stone.blackgranite.tiny", false, GregTech_API.sBlockGranites, 0, -1, 1, 50, 48, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.blackgranite.small", false, GregTech_API.sBlockGranites, 0, -1, 1, 100, 96, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.blackgranite.medium", false, GregTech_API.sBlockGranites, 0, -1, 1, 200, 144, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.blackgranite.large", false, GregTech_API.sBlockGranites, 0, -1, 1, 300, 192, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.blackgranite.huge", false, GregTech_API.sBlockGranites, 0, -1, 1, 400, 240, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.redgranite.tiny", false, GregTech_API.sBlockGranites, 8, -1, 1, 50, 48, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.redgranite.small", false, GregTech_API.sBlockGranites, 8, -1, 1, 100, 96, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.redgranite.medium", false, GregTech_API.sBlockGranites, 8, -1, 1, 200, 144, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.redgranite.large", false, GregTech_API.sBlockGranites, 8, -1, 1, 300, 192, 0, 120, null, false);
    new GT_Worldgen_Stone("nether.stone.redgranite.huge", false, GregTech_API.sBlockGranites, 8, -1, 1, 400, 240, 0, 120, null, false);
    
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.copper", true, 60, 120, 32, !tPFAA, true, true, Materials.Copper);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.tin", true, 60, 120, 32, !tPFAA, true, true, Materials.Tin);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.bismuth", true, 80, 120, 8, !tPFAA, true, false, Materials.Bismuth);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.coal", true, 60, 100, 24, !tPFAA, false, false, Materials.Coal);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.iron", true, 40, 80, 16, !tPFAA, true, true, Materials.Iron);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.lead", true, 40, 80, 16, !tPFAA, true, true, Materials.Lead);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.zinc", true, 30, 60, 12, !tPFAA, true, true, Materials.Zinc);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.gold", true, 20, 40, 8, !tPFAA, true, true, Materials.Gold);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.silver", true, 20, 40, 8, !tPFAA, true, true, Materials.Silver);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.nickel", true, 20, 40, 8, !tPFAA, true, true, Materials.Nickel);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.lapis", true, 20, 40, 4, !tPFAA, false, false, Materials.Lapis);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.diamond", true, 5, 10, 2, !tPFAA, true, false, Materials.Diamond);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.emerald", true, 5, 250, 1, !tPFAA, true, false, Materials.Emerald);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.ruby", true, 5, 250, 1, !tPFAA, true, false, Materials.Ruby);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.sapphire", true, 5, 250, 1, !tPFAA, true, false, Materials.Sapphire);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.greensapphire", true, 5, 250, 1, !tPFAA, true, false, Materials.GreenSapphire);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.olivine", true, 5, 250, 1, !tPFAA, true, false, Materials.Olivine);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.topaz", true, 5, 250, 1, !tPFAA, true, false, Materials.Topaz);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.tanzanite", true, 5, 250, 1, !tPFAA, true, false, Materials.Tanzanite);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.amethyst", true, 5, 250, 1, !tPFAA, true, false, Materials.Amethyst);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.opal", true, 5, 250, 1, !tPFAA, true, false, Materials.Opal);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.jasper", true, 5, 250, 1, !tPFAA, true, false, Materials.Jasper);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.bluetopaz", true, 5, 250, 1, !tPFAA, true, false, Materials.BlueTopaz);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.amber", true, 5, 250, 1, !tPFAA, true, false, Materials.Amber);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.foolsruby", true, 5, 250, 1, !tPFAA, true, false, Materials.FoolsRuby);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.garnetred", true, 5, 250, 1, !tPFAA, true, false, Materials.GarnetRed);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.garnetyellow", true, 5, 250, 1, !tPFAA, true, false, Materials.GarnetYellow);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.redstone", true, 5, 20, 8, !tPFAA, true, false, Materials.Redstone);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.platinum", true, 20, 40, 8, false, false, true, Materials.Platinum);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.iridium", true, 20, 40, 8, false, false, true, Materials.Iridium);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.netherquartz", true, 30, 120, 64, false, true, false, Materials.NetherQuartz);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.saltpeter", true, 10, 60, 8, false, true, false, Materials.Saltpeter);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.sulfur_n", true, 10, 60, 32, false, true, false, Materials.Sulfur);
    new GT_Worldgen_GT_Ore_SmallPieces("ore.small.sulfur_o", true, 5, 15, 8, !tPFAA, false, false, Materials.Sulfur);
    
    int i = 0;
    for (int j = GregTech_API.sWorldgenFile.get("worldgen", "AmountOfCustomSmallOreSlots", 16); i < j; i++) {
      new GT_Worldgen_GT_Ore_SmallPieces("ore.small.custom." + (i < 10 ? "0" : "") + i, false, 0, 0, 0, false, false, false, Materials._NULL);
    }
    new GT_Worldgen_GT_Ore_Layer("ore.mix.naquadah", false, 10, 60, 10, 5, 32, false, false, true, Materials.Naquadah, Materials.Naquadah, Materials.Naquadah, Materials.NaquadahEnriched);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.lignite", true, 50, 130, 160, 8, 32, !tPFAA, false, false, Materials.Lignite, Materials.Lignite, Materials.Lignite, Materials.Coal);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.coal", true, 50, 80, 80, 6, 32, !tPFAA, false, false, Materials.Coal, Materials.Coal, Materials.Coal, Materials.Lignite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.magnetite", true, 50, 120, 160, 3, 32, !tPFAA, true, false, Materials.Magnetite, Materials.Magnetite, Materials.Iron, Materials.VanadiumMagnetite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.gold", true, 60, 80, 160, 3, 32, !tPFAA, false, false, Materials.Magnetite, Materials.Magnetite, Materials.VanadiumMagnetite, Materials.Gold);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.iron", true, 10, 40, 120, 4, 24, !tPFAA, true, false, Materials.BrownLimonite, Materials.YellowLimonite, Materials.BandedIron, Materials.Malachite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.cassiterite", true, 40, 120, 50, 5, 24, !tPFAA, false, true, Materials.Tin, Materials.Tin, Materials.Cassiterite, Materials.Tin);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.tetrahedrite", true, 80, 120, 70, 4, 24, !tPFAA, true, false, Materials.Tetrahedrite, Materials.Tetrahedrite, Materials.Copper, Materials.Stibnite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.netherquartz", true, 40, 80, 80, 5, 24, false, true, false, Materials.NetherQuartz, Materials.NetherQuartz, Materials.NetherQuartz, Materials.NetherQuartz);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.sulfur", true, 5, 20, 100, 5, 24, false, true, false, Materials.Sulfur, Materials.Sulfur, Materials.Pyrite, Materials.Sphalerite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.copper", true, 10, 30, 80, 4, 24, !tPFAA, true, false, Materials.Chalcopyrite, Materials.Iron, Materials.Pyrite, Materials.Copper);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.bauxite", true, 50, 90, 80, 4, 24, !tPFAA, tPFAA, false, Materials.Bauxite, Materials.Bauxite, Materials.Aluminium, Materials.Ilmenite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.salts", true, 50, 60, 50, 3, 24, !tPFAA, false, false, Materials.RockSalt, Materials.Salt, Materials.Lepidolite, Materials.Spodumene);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.redstone", true, 10, 40, 60, 3, 24, !tPFAA, true, false, Materials.Redstone, Materials.Redstone, Materials.Ruby, Materials.Cinnabar);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.soapstone", true, 10, 40, 40, 3, 16, !tPFAA, false, false, Materials.Soapstone, Materials.Talc, Materials.Glauconite, Materials.Pentlandite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.nickel", true, 10, 40, 40, 3, 16, !tPFAA, true, true, Materials.Garnierite, Materials.Nickel, Materials.Cobaltite, Materials.Pentlandite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.platinum", true, 40, 50, 5, 3, 16, !tPFAA, false, true, Materials.Cooperite, Materials.Palladium, Materials.Platinum, Materials.Iridium);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.pitchblende", true, 10, 40, 40, 3, 16, !tPFAA, false, false, Materials.Pitchblende, Materials.Pitchblende, Materials.Uranium, Materials.Uraninite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.plutonium", true, 20, 30, 10, 3, 16, !tPFAA, false, false, Materials.Uraninite, Materials.Uraninite, Materials.Plutonium, Materials.Uranium);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.monazite", true, 20, 40, 30, 3, 16, !tPFAA, tPFAA, false, Materials.Bastnasite, Materials.Bastnasite, Materials.Monazite, Materials.Neodymium);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.molybdenum", true, 20, 50, 5, 3, 16, !tPFAA, false, true, Materials.Wulfenite, Materials.Molybdenite, Materials.Molybdenum, Materials.Powellite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.tungstate", true, 20, 50, 10, 3, 16, !tPFAA, false, true, Materials.Scheelite, Materials.Scheelite, Materials.Tungstate, Materials.Lithium);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.sapphire", true, 10, 40, 60, 3, 16, !tPFAA, tPFAA, tPFAA, Materials.Almandine, Materials.Pyrope, Materials.Sapphire, Materials.GreenSapphire);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.manganese", true, 20, 30, 20, 3, 16, !tPFAA, false, true, Materials.Grossular, Materials.Spessartine, Materials.Pyrolusite, Materials.Tantalite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.quartz", true, 40, 80, 60, 3, 16, !tPFAA, tPFAA, false, Materials.Quartzite, Materials.Barite, Materials.CertusQuartz, Materials.CertusQuartz);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.diamond", true, 5, 20, 40, 2, 16, !tPFAA, false, false, Materials.Graphite, Materials.Graphite, Materials.Diamond, Materials.Coal);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.olivine", true, 10, 40, 60, 3, 16, !tPFAA, false, true, Materials.Bentonite, Materials.Magnesite, Materials.Olivine, Materials.Glauconite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.apatite", true, 40, 60, 60, 3, 16, !tPFAA, false, false, Materials.Apatite, Materials.Apatite, Materials.Phosphorus, Materials.Phosphate);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.galena", true, 30, 60, 40, 5, 16, !tPFAA, false, false, Materials.Galena, Materials.Galena, Materials.Silver, Materials.Lead);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.lapis", true, 20, 50, 40, 5, 16, !tPFAA, false, true, Materials.Lazurite, Materials.Sodalite, Materials.Lapis, Materials.Calcite);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.beryllium", true, 5, 30, 30, 3, 16, !tPFAA, false, true, Materials.Beryllium, Materials.Beryllium, Materials.Emerald, Materials.Thorium);
    
    i = 0;
    for (int j = GregTech_API.sWorldgenFile.get("worldgen", "AmountOfCustomLargeVeinSlots", 16); i < j; i++) {
      new GT_Worldgen_GT_Ore_Layer("ore.mix.custom." + (i < 10 ? "0" : "") + i, false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    }
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.00", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.01", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.02", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.03", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.04", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.05", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.06", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.07", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.08", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.09", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.10", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.11", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.12", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.13", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.14", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
    new GT_Worldgen_GT_Ore_Layer("ore.mix.custom.15", false, 0, 0, 0, 0, 0, false, false, false, Materials._NULL, Materials._NULL, Materials._NULL, Materials._NULL);
  }
}


/* Location:           F:\Torrent\minecraft\jd-gui-0.3.6.windows\gregtech_1.7.10-5.07.07-dev.jar
 * Qualified Name:     gregtech.loaders.postload.GT_Worldgenloader
 * JD-Core Version:    0.7.0.1
 */