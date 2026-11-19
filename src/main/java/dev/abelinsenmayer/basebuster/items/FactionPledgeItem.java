package dev.abelinsenmayer.basebuster.items;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.client.FactionPledgeScreen;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * A paper certificate for pledging to a faction. Stores the bound faction's
 * team name in the {@link BaseBuster#FACTION} data component. Interactions:
 * sneak-right-click always opens the creation GUI; right-click on an unbound
 * pledge binds it to the player's current faction; right-click on a bound
 * pledge joins its faction and consumes it. The {@link FactionPledgeScreen}
 * class reference resolves lazily on the physical client only, so this stays
 * safe on dedicated servers.
 */
public class FactionPledgeItem extends Item {
    public FactionPledgeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String boundFaction = stack.get(BaseBuster.FACTION.get());

        if (player.isShiftKeyDown()) {
            // Sneak-right-click: always the faction-creation GUI
            if (level.isClientSide) {
                Minecraft.getInstance().setScreen(new FactionPledgeScreen());
            }
        } else if (boundFaction != null) {
            if (!level.isClientSide) {
                adoptFaction(level, player, stack, boundFaction);
            }
        } else {
            if (!level.isClientSide) {
                bindToCurrentFaction(level, player, stack);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Binds an unbound pledge to the player's current faction; no-ops if they have none. */
    private void bindToCurrentFaction(Level level, Player player, ItemStack stack) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        PlayerTeam team = server.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team != null) {
            stack.set(BaseBuster.FACTION.get(), team.getName());
        }
    }

    /** Joins the pledge's faction and consumes it. addPlayerToTeam also ejects the player from their previous team, so bound pledges double as faction-swaps. */
    private void adoptFaction(Level level, Player player, ItemStack stack, String boundFaction) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(boundFaction);
        if (team == null) {
            player.sendSystemMessage(
                    Component.translatable("item.basebuster.faction_pledge.no_such_faction", boundFaction));
            return;
        }
        if (scoreboard.getPlayersTeam(player.getScoreboardName()) == team) {
            return; // already a member -- don't consume the pledge
        }
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        stack.consume(1, player);
        player.sendSystemMessage(
                Component.translatable("item.basebuster.faction_pledge.joined", team.getDisplayName()));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        String boundFaction = stack.get(BaseBuster.FACTION.get());
        if (boundFaction != null) {
            // Show the faction name in its team color (gray fallback if the
            // team is gone or colorless)
            ChatFormatting color = ChatFormatting.GRAY;
            if (Minecraft.getInstance().level != null) {
                PlayerTeam team = Minecraft.getInstance().level.getScoreboard().getPlayerTeam(boundFaction);
                if (team != null && team.getColor().isColor()) {
                    color = team.getColor();
                }
            }
            tooltipComponents.add(Component.translatable(
                    "item.basebuster.faction_pledge.bound_to",
                    Component.literal(boundFaction).withStyle(color)));
            tooltipComponents.add(
                    Component.translatable("item.basebuster.faction_pledge.bound_hint_adopt"));
            tooltipComponents.add(
                    Component.translatable("item.basebuster.faction_pledge.bound_hint_create"));
        } else {
            tooltipComponents.add(
                    Component.translatable("item.basebuster.faction_pledge.unbound_hint_bind"));
            tooltipComponents.add(
                    Component.translatable("item.basebuster.faction_pledge.unbound_hint_create"));
        }
    }
}
