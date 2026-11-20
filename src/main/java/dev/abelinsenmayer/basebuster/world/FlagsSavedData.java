package dev.abelinsenmayer.basebuster.world;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
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
    // Team name -> banner design its members last stamped on one of their
    // flags. Applied to every flag the team owns, including ones its members
    // place or capture later. Stored only on the overworld's copy of this
    // data (see getOverworld).
    private Map<String, FlagDesign> teamDesigns = new HashMap<>();
    // Flag position -> its owning team and base name, mirrored from the flag's
    // block entity so base names stay known (and unique per team) while the
    // flag's chunk is unloaded. Written by
    // BaseFlagBlockEntity#syncSavedFlagInfo; cleared with the flag's location.
    private Map<BlockPos, FlagInfo> flagInfo = new HashMap<>();

    /** A banner design stamped on a flag: base color plus pattern layers. */
    public record FlagDesign(DyeColor color, BannerPatternLayers patterns) {}

    /** The ownership and name recorded for one flag; either field may be null. */
    public record FlagInfo(@Nullable String ownerTeam, @Nullable String baseName) {}

    /** The banner design the team last applied to a flag, or null if it never has. */
    @Nullable
    public FlagDesign getTeamDesign(String teamName) {
        return this.teamDesigns.get(teamName);
    }

    public void setTeamDesign(String teamName, FlagDesign design) {
        this.teamDesigns.put(teamName, design);
        this.setDirty();
    }

    public Set<BlockPos> getFlagLocations() {
        return flagLocations;
    }

    /** The recorded team/name for the flag at {@code pos}, or null if none. */
    @Nullable
    public FlagInfo getFlagInfo(BlockPos pos) {
        return this.flagInfo.get(pos);
    }

    /** Records (or replaces) the owning team and base name for a flag. */
    public void setFlagInfo(BlockPos pos, FlagInfo info) {
        this.flagInfo.put(pos, info);
        this.setDirty();
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
        this.flagInfo.remove(flagLocation);
        this.setDirty();
    }

    /**
     * Whether another flag owned by {@code teamName} is already named
     * {@code baseName}, scanning the flag info recorded in every dimension.
     * {@code exceptLevel}/{@code exceptPos} exempt the flag being renamed so
     * it doesn't collide with its own record.
     */
    public static boolean isBaseNameTaken(MinecraftServer server, String teamName, String baseName,
            ServerLevel exceptLevel, BlockPos exceptPos) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Map.Entry<BlockPos, FlagInfo> entry : getFromLevel(level).flagInfo.entrySet()) {
                if (level == exceptLevel && entry.getKey().equals(exceptPos)) {
                    continue;
                }
                FlagInfo info = entry.getValue();
                if (teamName.equals(info.ownerTeam()) && baseName.equals(info.baseName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The smallest "Base N" name not yet taken by a flag of {@code teamName},
     * used to name newly placed or captured flags uniquely.
     */
    public static String nextBaseName(MinecraftServer server, String teamName,
            ServerLevel exceptLevel, BlockPos exceptPos) {
        for (int i = 1;; i++) {
            String candidate = "Base " + i;
            if (!isBaseNameTaken(server, teamName, candidate, exceptLevel, exceptPos)) {
                return candidate;
            }
        }
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
        if (tag.contains("flag_info")) {
            ListTag infoList = tag.getList("flag_info", Tag.TAG_COMPOUND);
            for (int i = 0; i < infoList.size(); i++) {
                CompoundTag infoTag = infoList.getCompound(i);
                data.flagInfo.put(
                        BlockPos.of(infoTag.getLong("pos")),
                        new FlagInfo(
                                infoTag.contains("team") ? infoTag.getString("team") : null,
                                infoTag.contains("name") ? infoTag.getString("name") : null));
            }
        }
        if (tag.contains("team_designs")) {
            CompoundTag designsTag = tag.getCompound("team_designs");
            for (String key : designsTag.getAllKeys()) {
                CompoundTag designTag = designsTag.getCompound(key);
                BannerPatternLayers patterns = BannerPatternLayers.CODEC
                        .parse(lookupProvider.createSerializationContext(NbtOps.INSTANCE), designTag.get("patterns"))
                        .result()
                        .orElse(BannerPatternLayers.EMPTY);
                data.teamDesigns.put(
                        key,
                        new FlagDesign(DyeColor.byId(designTag.getInt("color")), patterns));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLongArray("flag_locations", this.flagLocations.stream().map(BlockPos::asLong).toList());
        tag.putLongArray("captured_flag_locations", this.capturedFlagLocations.stream().map(BlockPos::asLong).toList());
        if (!this.flagInfo.isEmpty()) {
            ListTag infoList = new ListTag();
            this.flagInfo.forEach((pos, info) -> {
                CompoundTag infoTag = new CompoundTag();
                infoTag.putLong("pos", pos.asLong());
                if (info.ownerTeam() != null) {
                    infoTag.putString("team", info.ownerTeam());
                }
                if (info.baseName() != null) {
                    infoTag.putString("name", info.baseName());
                }
                infoList.add(infoTag);
            });
            tag.put("flag_info", infoList);
        }
        if (!this.teamDesigns.isEmpty()) {
            CompoundTag designsTag = new CompoundTag();
            this.teamDesigns.forEach((teamName, design) -> {
                CompoundTag designTag = new CompoundTag();
                designTag.putInt("color", design.color().getId());
                designTag.put("patterns", BannerPatternLayers.CODEC
                        .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), design.patterns())
                        .getOrThrow());
                designsTag.put(teamName, designTag);
            });
            tag.put("team_designs", designsTag);
        }
        return tag;
    }

    public static FlagsSavedData getFromLevel(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(FlagsSavedData::create, FlagsSavedData::load), "basebuster_flags");
    }

    /**
     * The overworld's flags data, fetched from any dimension. Per-team flag
     * designs live here so they follow the team's members across dimensions;
     * flag locations stay per-dimension via {@link #getFromLevel}.
     */
    public static FlagsSavedData getOverworld(ServerLevel level) {
        return getFromLevel(level.getServer().overworld());
    }
}
