package dev.abelinsenmayer.basebuster.items;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.scores.PlayerTeam;

/**
 * A villager carried on paper. Created by right-clicking a villager with an
 * unbound {@link FactionPledgeItem}: the villager's full NBT is stored in the
 * {@link DataComponents#ENTITY_DATA} component and the villager leaves the
 * world. Right-clicking the ground releases it -- but only inside the
 * protection sphere of a {@link BaseFlagBlock} owned by a player team (never
 * a monster faction's flag, which grants citizenship to no one). On release
 * the certificate is consumed, the villager joins the flag's roster of
 * citizens and the owning team's scoreboard team, and an unnamed villager is
 * given the name "Villager" so it displays overhead.
 */
public class CitizenshipCertificateItem extends Item {
    public CitizenshipCertificateItem(Properties properties) {
        super(properties);
    }

    /**
     * A certificate holding {@code villager}'s complete NBT. The caller
     * removes the villager from the world; loading the tag recreates it with
     * all of its data (inventory, offers, gossips, name) intact.
     *
     * <p>{@code saveAsPassenger} rather than {@code saveWithoutId}: the
     * {@link DataComponents#ENTITY_DATA} codec validates the tag against the
     * entity-type registry when the stack syncs to a client, and it rejects
     * any tag without an {@code "id"} -- which saveWithoutId omits.
     */
    public static ItemStack capture(Villager villager) {
        ItemStack certificate = new ItemStack(BaseBuster.CITIZENSHIP_CERTIFICATE.get());
        CompoundTag tag = new CompoundTag();
        villager.saveAsPassenger(tag);
        certificate.set(DataComponents.ENTITY_DATA, CustomData.of(tag));
        return certificate;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (!(context.getLevel() instanceof ServerLevel serverLevel) || player == null) {
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }

        ItemStack stack = context.getItemInHand();
        CustomData entityData = stack.get(DataComponents.ENTITY_DATA);
        if (entityData == null) {
            return InteractionResult.PASS;
        }

        // Same placement rule as a spawn egg: against solid ground the
        // villager appears on the clicked face, not inside the block
        BlockPos clickedPos = context.getClickedPos();
        BlockPos spawnPos = serverLevel.getBlockState(clickedPos)
                .getCollisionShape(serverLevel, clickedPos).isEmpty()
                ? clickedPos
                : clickedPos.relative(context.getClickedFace());

        BaseFlagBlockEntity flagEntity = coveringPlayerTeamFlag(serverLevel, spawnPos);
        if (flagEntity == null) {
            player.displayClientMessage(
                    Component.translatable("item.basebuster.citizenship_certificate.no_base")
                            .withStyle(ChatFormatting.RED),
                    true);
            return InteractionResult.SUCCESS;
        }

        Villager citizen = EntityType.VILLAGER.create(serverLevel);
        if (citizen == null) {
            return InteractionResult.FAIL;
        }
        citizen.load(entityData.copyTag());
        citizen.moveTo(
                spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                context.getRotation(), 0.0F);
        if (!citizen.hasCustomName()) {
            citizen.setCustomName(Component.literal("Villager"));
        }
        citizen.setCustomNameVisible(true);
        serverLevel.addFreshEntity(citizen);

        flagEntity.addCitizen(citizen);
        PlayerTeam team = flagEntity.getOwnerTeam();
        if (team != null) {
            serverLevel.getScoreboard().addPlayerToTeam(citizen.getScoreboardName(), team);
        }
        serverLevel.sendBlockUpdated(flagEntity.getBlockPos(), flagEntity.getBlockState(), flagEntity.getBlockState(), 3);

        // Not consume(): the certificate is spent even for Creative players,
        // so a stored villager can't be duplicated.
        stack.shrink(1);
        player.displayClientMessage(
                Component.translatable("item.basebuster.citizenship_certificate.placed",
                        citizen.getDisplayName(),
                        team != null ? team.getDisplayName() : Component.literal(flagEntity.getOwnerTeamName()))
                        .withStyle(ChatFormatting.GREEN),
                true);
        return InteractionResult.SUCCESS;
    }

    /**
     * The block entity of the nearest flag whose protection sphere covers
     * {@code pos} and whose owner is a player team -- or null, which is also
     * the answer for faction-owned and unowned flags: monsters grant no
     * citizenship.
     */
    @Nullable
    private static BaseFlagBlockEntity coveringPlayerTeamFlag(ServerLevel level, BlockPos pos) {
        double maxRadiusSqr = Math.pow(BaseFlagBlock.MAX_PROTECTION_RADIUS, 2);
        return FlagsSavedData.getFromLevel(level).getFlagLocations().stream()
                .filter(flagPos -> flagPos.distSqr(pos) <= maxRadiusSqr)
                .filter(flagPos -> flagPos.distSqr(pos)
                        <= Math.pow(BaseFlagBlock.protectionRadius(level, flagPos), 2))
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(pos)))
                .map(flagPos -> level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity entity ? entity : null)
                .filter(Objects::nonNull)
                .filter(BaseFlagBlockEntity::isPlayerTeamOwned)
                .orElse(null);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomData entityData = stack.get(DataComponents.ENTITY_DATA);
        if (entityData != null) {
            CompoundTag tag = entityData.copyTag();
            Component name = villagerName(tag, context.registries());
            if (name != null) {
                tooltipComponents.add(Component.translatable(
                        "item.basebuster.citizenship_certificate.bound_to", name)
                        .withStyle(ChatFormatting.GRAY));
            }
            Component profession = villagerProfession(tag);
            if (profession != null) {
                tooltipComponents.add(Component.translatable(
                        "item.basebuster.citizenship_certificate.profession", profession)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        tooltipComponents.add(Component.translatable(
                "item.basebuster.citizenship_certificate.hint_place")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** The bound villager's custom name, or null while unnamed or before registries are known. */
    @Nullable
    private static Component villagerName(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        if (registries == null || !tag.contains("CustomName")) {
            return null;
        }
        return Component.Serializer.fromJson(tag.getString("CustomName"), registries);
    }

    /**
     * The bound villager's profession as its translation component (e.g.
     * "Librarian"), or null while unprofessioned -- nitwits and the
     * professionless report nothing.
     */
    @Nullable
    private static Component villagerProfession(CompoundTag tag) {
        if (!tag.contains("VillagerData")) {
            return null;
        }
        String professionId = tag.getCompound("VillagerData").getString("profession");
        if (professionId.isEmpty()) {
            return null;
        }
        ResourceLocation profession = ResourceLocation.tryParse(professionId);
        if (profession == null || "none".equals(profession.getPath())) {
            return null;
        }
        return Component.translatable(
                "entity." + profession.getNamespace() + ".villager." + profession.getPath());
    }
}
