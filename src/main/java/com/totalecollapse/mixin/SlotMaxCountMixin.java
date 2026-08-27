package com.totalecollapse.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.totalecollapse.StackItems;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/**
 * Slot#getMaxStackSize returns a flat 99 in vanilla and is the ceiling a GUI
 * refuses to go past. Only Slot's own implementation is patched, so any Slot
 * subclass that overrides the method — armour slots, crafting result slots,
 * beacon payment slots, and equivalents added by other mods — keeps its own
 * answer untouched. That is exactly the behaviour we want.
 */
@Mixin(Slot.class)
public abstract class SlotMaxCountMixin {

    @Shadow
    @Final
    public Container container;

    @Shadow
    public abstract int getContainerSlot();

    @Inject(method = "getMaxStackSize", at = @At("RETURN"), cancellable = true)
    private void totalecollapse$raiseSlotLimit(CallbackInfoReturnable<Integer> callback) {
        if (StackItems.isRestrictedSlot(this.container, this.getContainerSlot())) {
            return;
        }

        int vanilla = callback.getReturnValueI();
        int raised = StackItems.slotLimit(vanilla);

        if (raised != vanilla) {
            callback.setReturnValue(raised);
        }
    }
}
