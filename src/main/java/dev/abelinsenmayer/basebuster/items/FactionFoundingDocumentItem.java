package dev.abelinsenmayer.basebuster.items;

import dev.abelinsenmayer.basebuster.client.BaseFlagScreen;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The charter that founds a faction. Right-clicking opens the faction
 * creation GUI ({@link BaseFlagScreen} in create mode); when the player
 * clicks Create Faction the server creates the team, seats the founder
 * on it, and consumes the document -- see
 * {@link dev.abelinsenmayer.basebuster.network.CreateFactionPayload}.
 * The {@link BaseFlagScreen} class reference resolves lazily on the
 * physical client only, so this stays safe on dedicated servers.
 */
public class FactionFoundingDocumentItem extends Item {
    public FactionFoundingDocumentItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            Minecraft.getInstance().setScreen(new BaseFlagScreen());
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(
                Component.translatable("item.basebuster.faction_founding_document.hint_create"));
    }
}
