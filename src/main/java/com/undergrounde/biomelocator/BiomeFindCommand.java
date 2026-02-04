package com.undergrounde.biomelocator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;

import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class BiomeFindCommand {
    
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(literal("findbiome")
            .then(argument("biome", StringArgumentType.string())
                .suggests(biomeSuggestions())
                .executes(context -> findBiome(context, StringArgumentType.getString(context, "biome")))));
    }

    private static SuggestionProvider<FabricClientCommandSource> biomeSuggestions() {
        return (context, builder) -> {
            MinecraftClient client = context.getSource().getClient();
            if (client.world != null) {
                Registry<Biome> biomeRegistry = client.world.getRegistryManager().get(RegistryKeys.BIOME);
                return suggestBiomes(builder, biomeRegistry);
            }
            return builder.buildFuture();
        };
    }

    private static CompletableFuture<Suggestions> suggestBiomes(SuggestionsBuilder builder, Registry<Biome> biomeRegistry) {
        biomeRegistry.getKeys().forEach(key -> {
            String biomeId = key.getValue().toString();
            builder.suggest(biomeId);
        });
        return builder.buildFuture();
    }

    private static int findBiome(CommandContext<FabricClientCommandSource> context, String biomeId) {
        MinecraftClient client = context.getSource().getClient();
        
        if (client.world == null || client.player == null) {
            context.getSource().sendError(Text.literal("You must be in a world to use this command!"));
            return 0;
        }

        Identifier biomeName;
        try {
            biomeName = Identifier.of(biomeId);
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Invalid biome name: " + biomeId));
            return 0;
        }

        Registry<Biome> biomeRegistry = client.world.getRegistryManager().get(RegistryKeys.BIOME);
        RegistryKey<Biome> biomeKey = RegistryKey.of(RegistryKeys.BIOME, biomeName);
        
        if (!biomeRegistry.contains(biomeKey)) {
            context.getSource().sendError(Text.literal("Biome not found: " + biomeId));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§eSearching for biome: §6" + biomeId + "§e..."));

        new Thread(() -> {
            BlockPos playerPos = client.player.getBlockPos();
            BiomeSource biomeSource = client.world.getChunkManager().getChunkGenerator().getBiomeSource();
            
            BlockPos foundPos = findNearestBiome(biomeSource, biomeRegistry, biomeKey, playerPos, 6400, 8);
            
            client.execute(() -> {
                if (foundPos != null) {
                    int distance = (int) Math.sqrt(playerPos.getSquaredDistance(foundPos));
                    context.getSource().sendFeedback(Text.literal(
                        String.format("§aFound §6%s§a at §b[%d, ~, %d]§a (§e%d blocks away§a)", 
                        biomeId, foundPos.getX(), foundPos.getZ(), distance)
                    ));
                } else {
                    context.getSource().sendError(Text.literal("Could not find biome: " + biomeId + " within search radius"));
                }
            });
        }).start();

        return 1;
    }

    private static BlockPos findNearestBiome(BiomeSource biomeSource, Registry<Biome> biomeRegistry, 
                                            RegistryKey<Biome> targetBiome, BlockPos center, 
                                            int radius, int increment) {
        int centerX = center.getX();
        int centerZ = center.getZ();
        int y = 64;

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        
        for (int r = 0; r < radius; r += increment) {
            for (int angle = 0; angle < 360; angle += 5) {
                double radians = Math.toRadians(angle);
                int x = centerX + (int) (r * Math.cos(radians));
                int z = centerZ + (int) (r * Math.sin(radians));
                
                mutablePos.set(x, y, z);
                RegistryEntry<Biome> biomeEntry = biomeSource.getBiome(x >> 2, y >> 2, z >> 2, 
                    biomeSource.getClass().getClassLoader() != null ? null : null);
                
                if (biomeEntry.matchesKey(targetBiome)) {
                    return mutablePos.toImmutable();
                }
            }
        }
        
        return null;
    }
}