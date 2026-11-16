package dev.abelinsenmayer.basebuster.world;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class FlagsSavedData extends SavedData {
    private Set<BlockPos> flagLocations = new HashSet<>();
    // Subset of flagLocations whose flags are owned by the raider faction.
    // Tracked here so raiders far from the flag's chunk know not to path to it.
    private Set<BlockPos> capturedFlagLocations = new HashSet<>();
    // Player id -> banner design they last stamped on one of their flags.
    // Applied to every flag they own, including ones they place later.
    private Map<UUID, FlagDesign> playerDesigns = new HashMap<>();

    /** A banner design stamped on a flag: base color plus pattern layers. */
    public record FlagDesign(DyeColor color, BannerPatternLayers patterns) {}

    /** The banner design the player last applied to a flag, or null if they never have. */
    @Nullable
    public FlagDesign getPlayerDesign(UUID playerId) {
        return this.playerDesigns.get(playerId);
    }

    public void setPlayerDesign(UUID playerId, FlagDesign design) {
        this.playerDesigns.put(playerId, design);
        this.setDirty();
    }

    public Set<BlockPos> getFlagLocations() {
        return flagLocations;
    }

    public Set<BlockPos> getCapturedFlagLocations() {
        return capturedFlagLocations;
    }

    public void addCapturedFlagLocation(BlockPos flagLocation) {
        this.capturedFlagLocations.add(flagLocation);
        this.setDirty();
    }

    public void removeCapturedFlagLocation(BlockPos flagLocation) {
        this.capturedFlagLocations.remove(flagLocation);
        this.setDirty();
    }

    public void setFlagLocations(Set<BlockPos> flagLocations) {
        this.flagLocations = flagLocations;
        this.setDirty();
    }

    public void addFlagLocation(BlockPos flagLocation) {
        this.flagLocations.add(flagLocation);
        this.setDirty();
    }

    public void removeFlagLocation(BlockPos flagLocation) {
        this.flagLocations.remove(flagLocation);
        this.capturedFlagLocations.remove(flagLocation);
        this.setDirty();
    }

    public static FlagsSavedData create() {
        return new FlagsSavedData();
    }

    public static FlagsSavedData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        FlagsSavedData data = new FlagsSavedData();
        if (tag.contains("flag_locations")) {
            long[] storedFlagLocations = tag.getLongArray("flag_locations");
            data.setFlagLocations(Arrays.stream(storedFlagLocations)
                    .mapToObj(BlockPos::of)
                    .collect(Collectors.toCollection(HashSet::new)));
        }
        if (tag.contains("captured_flag_locations")) {
            long[] storedCaptured = tag.getLongArray("captured_flag_locations");
            data.capturedFlagLocations = Arrays.stream(storedCaptured)
                    .mapToObj(BlockPos::of)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        if (tag.contains("player_designs")) {
            CompoundTag designsTag = tag.getCompound("player_designs");
            for (String key : designsTag.getAllKeys()) {
                CompoundTag designTag = designsTag.getCompound(key);
                BannerPatternLayers patterns = BannerPatternLayers.CODEC
                        .parse(lookupProvider.createSerializationContext(NbtOps.INSTANCE), designTag.get("patterns"))
                        .result()
                        .orElse(BannerPatternLayers.EMPTY);
                data.playerDesigns.put(
                        UUID.fromString(key),
                        new FlagDesign(DyeColor.byId(designTag.getInt("color")), patterns));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLongArray("flag_locations", this.flagLocations.stream().map(BlockPos::asLong).toList());
        tag.putLongArray("captured_flag_locations", this.capturedFlagLocations.stream().map(BlockPos::asLong).toList());
        if (!this.playerDesigns.isEmpty()) {
            CompoundTag designsTag = new CompoundTag();
            this.playerDesigns.forEach((playerId, design) -> {
                CompoundTag designTag = new CompoundTag();
                designTag.putInt("color", design.color().getId());
                designTag.put("patterns", BannerPatternLayers.CODEC
                        .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), design.patterns())
                        .getOrThrow());
                designsTag.put(playerId.toString(), designTag);
            });
            tag.put("player_designs", designsTag);
        }
        return tag;
    }

    public static FlagsSavedData getFromLevel(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(FlagsSavedData::create, FlagsSavedData::load), "basebuster_flags");
    }
}
